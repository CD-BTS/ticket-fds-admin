package com.bts.fairticket.risk.repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bts.fairticket.risk.entity.RiskAssessmentCursor;

public interface RiskAssessmentCursorRepository extends JpaRepository<RiskAssessmentCursor, String> {

	/**
	 * 평가 대상: 분석 시각(cutoff)까지 발생한 이벤트 중 아직 평가에 반영되지 않은 것이 있는 사용자. 오래 기다린 순.
	 * <p>반영되지 않은 이벤트는 두 가지다.
	 * <ul>
	 *   <li>마지막 평가 이후 새로 들어온 이벤트: {@code event_id > last_event_id}. 늦게 도착한 이벤트도 여기에 잡힌다.</li>
	 *   <li>마지막 평가 때는 발생 시각이 분석 시각보다 뒤라 집계에서 빠졌던 이벤트: {@code occurred_at > checked_at}.
	 *       커서가 event_id로는 이미 지나갔더라도 그 시각이 되면 다시 대상이 된다.</li>
	 * </ul>
	 * 그래서 발생 시각이 미래인 이벤트가 있어도 그 뒤에 들어온 정상 이벤트의 평가를 막지 않고(#63),
	 * 미래 이벤트 자체도 누락되지 않는다. checked_at은 그 평가의 분석 시각과 같은 값(초 단위)이므로,
	 * 지난 평가에 반영된 이벤트(occurred_at <= checked_at)가 다시 잡히지도 않는다.
	 * <p>advanceToEventId는 이번에 커서를 옮길 위치(cutoff까지 발생한 새 이벤트의 최대 event_id, 없으면 현재 위치)이고,
	 * seenLastEventId · seenCheckedAt은 조회 시점의 커서 값이다(커서가 없으면 null). 결과를 저장하기 직전에
	 * 커서가 그대로인지 확인하는 데 쓴다.
	 */
	interface Candidate {
		String getUserId();
		Long getAdvanceToEventId();
		Long getSeenLastEventId();
		/** yyyy-MM-dd HH:mm:ss (UTC). JVM 시간대 변환을 피하려고 문자열로 받는다. */
		String getSeenCheckedAt();
	}

	@Query(value = """
			select n.user_id as userId, n.advance_to as advanceToEventId,
				n.seen_last_event_id as seenLastEventId, n.seen_checked_at as seenCheckedAt
			from (
				select e.user_id,
					c.last_event_id as seen_last_event_id,
					date_format(c.checked_at, '%Y-%m-%d %H:%i:%s') as seen_checked_at,
					greatest(coalesce(c.last_event_id, 0), max(e.event_id)) as advance_to,
					min(e.event_id) as first_pending_event_id
				from behavior_event e
				left join risk_assessment_cursor c on c.user_id = e.user_id
				where e.occurred_at <= cast(:cutoff as datetime(3))
				  and (e.event_id > coalesce(c.last_event_id, 0) or e.occurred_at > c.checked_at)
				group by e.user_id, c.last_event_id, c.checked_at
			) n
			order by n.first_pending_event_id
			limit :limit
			""", nativeQuery = true)
	List<Candidate> findCandidatesByCutoffText(@Param("cutoff") String cutoff, @Param("limit") int limit);

	/**
	 * @param cutoffUtc 분석 시각(UTC, 초 단위. checked_at에 그대로 기록되는 값). Feature 집계에 넘기는 calculatedAt과 같은 값이어야 한다.
	 */
	default List<Candidate> findCandidates(LocalDateTime cutoffUtc, int limit) {
		// 네이티브 쿼리에 LocalDateTime을 그대로 바인딩하면 JVM 시간대 기준으로 변환되어 UTC 값이 어긋난다.
		// occurred_at(UTC, datetime(3))과 같은 형식의 문자열로 넘겨 DB에서 그대로 비교한다.
		return findCandidatesByCutoffText(CUTOFF_FORMAT.format(cutoffUtc), limit);
	}

	DateTimeFormatter CUTOFF_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
}
