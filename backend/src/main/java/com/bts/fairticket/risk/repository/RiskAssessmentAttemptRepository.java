package com.bts.fairticket.risk.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bts.fairticket.risk.entity.RiskAssessmentAttempt;

public interface RiskAssessmentAttemptRepository extends JpaRepository<RiskAssessmentAttempt, Long> {

	Optional<RiskAssessmentAttempt> findFirstByUserIdOrderByAttemptIdDesc(String userId);

	/** 사용자별 가장 최근 평가 시도. 시도 이력이 없는 사용자는 결과에 없다. */
	@Query("""
		select t from RiskAssessmentAttempt t
		where t.userId in :userIds and t.attemptId = (
		    select max(x.attemptId) from RiskAssessmentAttempt x where x.userId = t.userId)
		""")
	List<RiskAssessmentAttempt> findLatestForUsers(@Param("userIds") List<String> userIds);
}
