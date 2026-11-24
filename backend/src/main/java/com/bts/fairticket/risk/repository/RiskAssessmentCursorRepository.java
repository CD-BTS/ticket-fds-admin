package com.bts.fairticket.risk.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bts.fairticket.risk.entity.RiskAssessmentCursor;

public interface RiskAssessmentCursorRepository extends JpaRepository<RiskAssessmentCursor, String> {

	/** 평가 대상: 마지막 평가 이후 새 이벤트(event_id 증가)가 들어온 사용자와 그 최대 event_id. 오래 기다린 순. */
	interface Candidate {
		String getUserId();
		Long getMaxEventId();
	}

	@Query(value = """
			select e.user_id as userId, max(e.event_id) as maxEventId
			from behavior_event e
			left join risk_assessment_cursor c on c.user_id = e.user_id
			group by e.user_id, c.last_event_id
			having max(e.event_id) > coalesce(c.last_event_id, 0)
			order by min(case when e.event_id > coalesce(c.last_event_id, 0) then e.event_id end)
			limit :limit
			""", nativeQuery = true)
	List<Candidate> findCandidates(@Param("limit") int limit);
}
