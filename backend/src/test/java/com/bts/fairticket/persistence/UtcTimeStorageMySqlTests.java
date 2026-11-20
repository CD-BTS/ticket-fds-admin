package com.bts.fairticket.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.bts.fairticket.behavior.entity.BehaviorEvent;
import com.bts.fairticket.behavior.repository.BehaviorEventRepository;
import com.bts.fairticket.risk.entity.RiskAssessment;
import com.bts.fairticket.risk.repository.RiskAssessmentRepository;
import com.bts.fairticket.user.entity.UserAccount;
import com.bts.fairticket.user.repository.UserAccountRepository;

/**
 * #47: JVM 시간대나 DB_URL의 serverTimezone과 관계없이 UTC LocalDateTime이 그대로 저장·조회되고,
 * DB 기본값(now())도 UTC로 채워지는지 확인한다. 시간대마다 JVM 기본값을 바꿔 가며 같은 결과를 기대한다.
 */
@Tag("mysql")
@SpringBootTest
@Transactional
class UtcTimeStorageMySqlTests {

	private static final TimeZone ORIGINAL = TimeZone.getDefault();

	@Autowired private EntityManager entityManager;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private UserAccountRepository users;
	@Autowired private BehaviorEventRepository events;
	@Autowired private RiskAssessmentRepository assessments;

	@AfterEach
	void restoreTimeZone() {
		TimeZone.setDefault(ORIGINAL);
	}

	@ParameterizedTest
	@ValueSource(strings = {"Asia/Seoul", "UTC", "America/New_York"})
	void storesAndLoadsUtcValuesUnchangedRegardlessOfJvmTimeZone(String zone) {
		TimeZone.setDefault(TimeZone.getTimeZone(zone));
		String id = UUID.randomUUID().toString();
		UserAccount user = users.saveAndFlush(new UserAccount(id, "utc-test", id));
		LocalDateTime occurredAt = LocalDateTime.of(2026, 11, 19, 6, 15, 3);

		BehaviorEvent saved = events.saveAndFlush(new BehaviorEvent(user, "utc-test", "evt-" + id, "BOOKING",
				"b-" + id, null, occurredAt, null));
		String stored = jdbc.queryForObject(
				"select date_format(occurred_at, '%Y-%m-%d %H:%i:%s') from behavior_event where event_id = ?",
				String.class, saved.getEventId());
		assertEquals("2026-11-19 06:15:03", stored, "DB에 저장된 값 (JVM " + zone + ")");

		entityManager.clear();
		assertEquals(occurredAt, events.findById(saved.getEventId()).orElseThrow().getOccurredAt(),
				"JPA로 다시 읽은 값 (JVM " + zone + ")");
	}

	@ParameterizedTest
	@ValueSource(strings = {"Asia/Seoul", "UTC", "America/New_York"})
	void databaseDefaultTimestampsAreUtc(String zone) {
		TimeZone.setDefault(TimeZone.getTimeZone(zone));
		String id = UUID.randomUUID().toString();
		UserAccount user = users.saveAndFlush(new UserAccount(id, "utc-test", id));
		RiskAssessment assessment = assessments.saveAndFlush(new RiskAssessment(user, 0.5, "utc-test"));

		Long skew = jdbc.queryForObject(
				"select abs(timestampdiff(second, assessed_at, utc_timestamp())) from risk_assessment where assessment_id = ?",
				Long.class, assessment.getAssessmentId());
		assertTrue(skew != null && skew < 120, "DB 기본값 now()가 UTC가 아님: 차이 " + skew + "초 (JVM " + zone + ")");

		entityManager.clear();
		LocalDateTime loaded = assessments.findById(assessment.getAssessmentId()).orElseThrow().getAssessedAt();
		long diff = Math.abs(Duration.between(loaded, LocalDateTime.now(ZoneOffset.UTC)).getSeconds());
		assertTrue(diff < 120, "JPA로 읽은 assessedAt이 UTC가 아님: 차이 " + diff + "초 (JVM " + zone + ")");
	}
}
