package com.bts.fairticket.risk.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import com.bts.fairticket.risk.client.AiInferenceClient;
import com.bts.fairticket.risk.client.AiInferenceClient.AiPredictRequest;
import com.bts.fairticket.risk.client.AiInferenceClient.AiPrediction;
import com.bts.fairticket.risk.client.AiInferenceClient.AiUnavailableException;
import com.bts.fairticket.risk.dto.UserCalculatedFeatures;
import com.bts.fairticket.risk.entity.RiskAssessment;
import com.bts.fairticket.risk.entity.RiskAssessmentCursor;
import com.bts.fairticket.risk.event.RiskAssessedEvent;
import com.bts.fairticket.risk.repository.RiskAssessmentCursorRepository;
import com.bts.fairticket.risk.repository.RiskAssessmentCursorRepository.Candidate;
import com.bts.fairticket.risk.repository.RiskAssessmentRepository;
import com.bts.fairticket.user.entity.UserAccount;
import com.bts.fairticket.user.repository.UserAccountRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RiskAssessmentServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-24T05:00:00.789Z");
	private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 11, 24, 5, 0, 0);

	@Mock RiskAssessmentCursorRepository cursors;
	@Mock FeatureAggregationService features;
	@Mock AiInferenceClient ai;
	@Mock UserAccountRepository users;
	@Mock RiskAssessmentRepository assessments;
	@Mock ApplicationEventPublisher events;
	@Mock PlatformTransactionManager transactions;

	private RiskAssessmentService service;

	@BeforeEach
	void setUp() {
		service = new RiskAssessmentService(cursors, features, ai, users, assessments, events, transactions,
				Clock.fixed(NOW, ZoneOffset.UTC), 2, 500);
		when(cursors.findById(any())).thenReturn(Optional.empty());
		when(users.findById(any())).thenAnswer(inv -> Optional.of(new UserAccount(inv.getArgument(0), "svc", "ext")));
		when(assessments.save(any())).thenAnswer(inv -> inv.getArgument(0));
	}

	private static Candidate candidate(String userId, long maxEventId) {
		return new Candidate() {
			public String getUserId() { return userId; }
			public Long getMaxEventId() { return maxEventId; }
		};
	}

	private static UserCalculatedFeatures features(Double variance) {
		return UserCalculatedFeatures.builder().bookingCount(7).avgBookingInterval(0.3).bookingIntervalVariance(variance)
				.cancelRate(null).cardReuseCount(1).ipBookingDensity(1).build();
	}

	private static AiPrediction ok(String userId, double score) {
		return new AiPrediction(userId, "OK", 0.5, score, score >= 0.5, "fds-if-v4.0.0", "2026-11-24T05:00:01Z", null);
	}

	private static AiPrediction status(String userId, String status) {
		return new AiPrediction(userId, status, null, null, null, "fds-if-v4.0.0", "2026-11-24T05:00:01Z",
				"INVALID".equals(status) ? List.of("features.bookingCount는 null일 수 없습니다.") : null);
	}

	@Test
	void savesOkResultsPublishesEventAndAdvancesCursorForEveryStatus() {
		when(cursors.findCandidates(500)).thenReturn(List.of(candidate("u1", 10), candidate("u2", 20), candidate("u3", 30)));
		when(features.aggregateFeatures(any(), eq(NOW_UTC))).thenReturn(features(0.01));
		when(ai.predictBatch(anyList())).thenAnswer(inv -> {
			List<AiPredictRequest> reqs = inv.getArgument(0);
			return reqs.stream().map(r -> switch (r.userId()) {
				case "u1" -> ok("u1", 0.72);
				case "u2" -> status("u2", "INSUFFICIENT_DATA");
				default -> status("u3", "INVALID");
			}).toList();
		});

		var summary = service.runOnce();

		assertEquals(new RiskAssessmentService.RunSummary(3, 1, 1, 1), summary);
		verify(ai, times(2)).predictBatch(anyList()); // batch-size 2 → 2 + 1
		var saved = ArgumentCaptor.forClass(RiskAssessment.class);
		verify(assessments, times(1)).save(saved.capture());
		assertEquals(0.72, saved.getValue().getAnomalyScore(), 1e-9);
		assertEquals("fds-if-v4.0.0", saved.getValue().getModelVersion());

		var published = ArgumentCaptor.forClass(RiskAssessedEvent.class);
		verify(events).publishEvent(published.capture());
		assertEquals("u1", published.getValue().userId());
		assertEquals(0.01, published.getValue().bookingIntervalVariance(), 1e-9);

		var cursor = ArgumentCaptor.forClass(RiskAssessmentCursor.class);
		verify(cursors, times(3)).save(cursor.capture());
		Map<String, RiskAssessmentCursor> byUser = cursor.getAllValues().stream()
				.collect(java.util.stream.Collectors.toMap(RiskAssessmentCursor::getUserId, c -> c));
		assertEquals(10L, byUser.get("u1").getLastEventId());
		assertEquals("OK", byUser.get("u1").getLastStatus());
		assertEquals("INSUFFICIENT_DATA", byUser.get("u2").getLastStatus());
		assertEquals("INVALID", byUser.get("u3").getLastStatus());
		assertEquals(NOW_UTC, byUser.get("u3").getCheckedAt());
	}

	@Test
	void sendsUtcCalculatedAtAndThirtyDayWindow() {
		when(cursors.findCandidates(500)).thenReturn(List.of(candidate("u1", 1)));
		when(features.aggregateFeatures("u1", NOW_UTC)).thenReturn(features(null));
		when(ai.predictBatch(anyList())).thenReturn(List.of(status("u1", "INSUFFICIENT_DATA")));

		service.runOnce();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<AiPredictRequest>> sent = ArgumentCaptor.forClass(List.class);
		verify(ai).predictBatch(sent.capture());
		AiPredictRequest req = sent.getValue().get(0);
		assertEquals("2026-11-24T05:00:00Z", req.calculatedAt());
		assertEquals(30, req.windowDays());
		assertNull(req.features().bookingIntervalVariance());
		assertEquals(7, req.features().bookingCount());
	}

	@Test
	void aiOutageLeavesCursorsUntouchedForRetry() {
		when(cursors.findCandidates(500)).thenReturn(List.of(candidate("u1", 10)));
		when(features.aggregateFeatures(any(), any())).thenReturn(features(null));
		when(ai.predictBatch(anyList())).thenThrow(new AiUnavailableException("down"));

		assertThrows(AiUnavailableException.class, () -> service.runOnce());
		verify(cursors, never()).save(any());
		verify(assessments, never()).save(any());
	}

	@Test
	void rejectsMismatchedUserOrOutOfRangeScoreWithoutSaving() {
		when(cursors.findCandidates(500)).thenReturn(List.of(candidate("u1", 10), candidate("u2", 11)));
		when(features.aggregateFeatures(any(), any())).thenReturn(features(null));
		when(ai.predictBatch(anyList())).thenReturn(List.of(ok("someone-else", 0.7), ok("u2", 1.5)));

		var summary = service.runOnce();

		assertEquals(2, summary.invalid());
		verify(assessments, never()).save(any());
		verify(events, never()).publishEvent(any());
	}

	@Test
	void noCandidatesMeansNoAiCall() {
		when(cursors.findCandidates(500)).thenReturn(List.of());
		assertEquals(new RiskAssessmentService.RunSummary(0, 0, 0, 0), service.runOnce());
		verifyNoInteractions(ai);
	}
}
