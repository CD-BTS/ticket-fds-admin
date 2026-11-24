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
	 * 평가 대상: 마지막 평가 이후 새 이벤트(event_id 증가)가 들어온 사용자와 이번에 커서를 옮길 위치. 오래 기다린 순.
	 * <p>Feature 집계는 occurred_at이 cutoff 이하인 이벤트만 반영하므로, 커서도 그 범위까지만 옮긴다.
	 * 새 이벤트 중 occurred_at이 cutoff보다 뒤인 것이 있으면 그중 가장 작은 event_id 바로 앞까지,
	 * 없으면 가장 큰 event_id까지다. 옮길 위치가 현재 커서보다 크지 않은 사용자는 대상에서 빠지고,
	 * 시각이 지난 뒤의 실행에서 다시 대상이 된다.
	 */
	interface Candidate {
		String getUserId();
		Long getAdvanceToEventId();
	}

	@Query(value = """
			select n.user_id as userId, n.advance_to as advanceToEventId
			from (
				select e.user_id,
					coalesce(c.last_event_id, 0) as last_event_id,
					coalesce(min(case when e.occurred_at > cast(:cutoff as datetime(3)) then e.event_id end) - 1, max(e.event_id)) as advance_to,
					min(e.event_id) as first_new_event_id
				from behavior_event e
				left join risk_assessment_cursor c on c.user_id = e.user_id
				where e.event_id > coalesce(c.last_event_id, 0)
				group by e.user_id, c.last_event_id
			) n
			where n.advance_to > n.last_event_id
			order by n.first_new_event_id
			limit :limit
			""", nativeQuery = true)
	List<Candidate> findCandidatesByCutoffText(@Param("cutoff") String cutoff, @Param("limit") int limit);

	/**
	 * @param cutoffUtc 분석 시각(UTC, 밀리초). Feature 집계에 넘기는 calculatedAt과 같은 값이어야 한다.
	 */
	default List<Candidate> findCandidates(LocalDateTime cutoffUtc, int limit) {
		// 네이티브 쿼리에 LocalDateTime을 그대로 바인딩하면 JVM 시간대 기준으로 변환되어 UTC 값이 어긋난다.
		// occurred_at(UTC, datetime(3))과 같은 형식의 문자열로 넘겨 DB에서 그대로 비교한다.
		return findCandidatesByCutoffText(CUTOFF_FORMAT.format(cutoffUtc), limit);
	}

	DateTimeFormatter CUTOFF_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
}
