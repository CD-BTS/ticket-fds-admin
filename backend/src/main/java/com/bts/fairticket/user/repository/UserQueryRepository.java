package com.bts.fairticket.user.repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import com.bts.fairticket.common.query.QueryPage;
import com.bts.fairticket.common.query.ServiceScopeQuery;
import com.bts.fairticket.common.response.PageResponse;
import com.bts.fairticket.common.exception.ApiException;
import com.bts.fairticket.common.response.ResponseCode;
import com.bts.fairticket.user.dto.UserQuery;
import com.bts.fairticket.user.dto.UserResponse;

@Repository
public class UserQueryRepository {
	private final EntityManager em;
	public UserQueryRepository(EntityManager em) { this.em = em; }

	public PageResponse<UserResponse> find(UserQuery filter, Set<String> services) {
		return find(filter, services, null);
	}

	public PageResponse<UserResponse> find(UserQuery filter, Set<String> services, String concertId) {
		var page = QueryPage.parse(filter.page(), filter.size(), filter.sort(), Map.of(
				"createdAt", "u.createdAt", "userId", "u.userId", "creditScore", "c.creditScore",
				// 미평가 사용자(점수 없음)는 오름차순 · 내림차순 모두 뒤에 둔다. 0점으로 취급하지 않는다 (#67).
				"anomalyScore", "case when a.anomalyScore is null then 1 else 0 end, a.anomalyScore"),
				"createdAt", "u.userId");
		if (filter.minCreditScore() != null && filter.maxCreditScore() != null
				&& filter.minCreditScore() > filter.maxCreditScore()) QueryPage.invalid();
		if (filter.minAnomalyScore() != null && filter.maxAnomalyScore() != null
				&& filter.minAnomalyScore() > filter.maxAnomalyScore()) QueryPage.invalid();
		if (filter.createdFrom() != null && filter.createdTo() != null
				&& !filter.createdFrom().isBefore(filter.createdTo())) QueryPage.invalid();
		for (var date : new java.time.LocalDateTime[]{filter.createdFrom(), filter.createdTo()}) {
			if (date != null && (date.getYear() < 1000 || date.getYear() > 9999)) QueryPage.invalid();
		}
		StringBuilder where = new StringBuilder(" where 1=1");
		Map<String, Object> parameters = new LinkedHashMap<>();
		if (services != null) {
			if (services.isEmpty()) return PageResponse.of(java.util.List.of(), page.page(), page.size(), 0);
			where.append(" and ").append(ServiceScopeQuery.predicate("u.serviceId"));
		}
		add(where, parameters, "userId", "u.userId =", filter.userId());
		add(where, parameters, "externalUserId", "u.externalUserId =", filter.externalUserId());
		add(where, parameters, "accountStatus", "u.accountStatus =", filter.accountStatus());
		add(where, parameters, "tier", "c.tier =", filter.tier());
		add(where, parameters, "minCreditScore", "c.creditScore >=", filter.minCreditScore());
		add(where, parameters, "maxCreditScore", "c.creditScore <=", filter.maxCreditScore());
		add(where, parameters, "createdFrom", "u.createdAt >=", filter.createdFrom());
		add(where, parameters, "createdTo", "u.createdAt <", filter.createdTo());
		// 이상 점수 조건은 최신 성공 평가(a)에 건다. 점수가 없는 사용자는 범위 조건에서 빠진다 (#67).
		add(where, parameters, "minAnomalyScore", "a.anomalyScore >=", filter.minAnomalyScore());
		add(where, parameters, "maxAnomalyScore", "a.anomalyScore <=", filter.maxAnomalyScore());
		if (filter.assessed() != null) {
			where.append(filter.assessed() ? " and a.assessmentId is not null" : " and a.assessmentId is null");
		}
		if (filter.keyword() != null) {
			// 내부 ID 또는 외부 사용자 ID 부분 일치. LIKE 특수문자는 그대로의 문자로 찾는다.
			where.append(" and (u.userId like :keyword escape '!' or u.externalUserId like :keyword escape '!')");
			parameters.put("keyword", "%" + filter.keyword().trim()
					.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
		}
		if (concertId != null) {
			where.append(" ").append("""
			    and exists (select e.eventId from BehaviorEvent e where e.user.userId = u.userId
			    and e.serviceId = u.serviceId and function('hex', e.serviceId) = function('hex', u.serviceId)
			    and e.eventType = 'BOOKING_ATTEMPT'
			    and function('JSON_UNQUOTE', function('JSON_EXTRACT', e.metadata, '$.concertId')) = :concertId
			    and function('hex', function('JSON_UNQUOTE', function('JSON_EXTRACT', e.metadata, '$.concertId'))) = function('hex', :concertId)
			    and function('JSON_UNQUOTE', function('JSON_EXTRACT', e.metadata, '$.stage')) = 'STARTED'
			    and function('JSON_UNQUOTE', function('JSON_EXTRACT', e.metadata, '$.status')) = 'ENTERED')
			    """);
			parameters.put("concertId", concertId);
		}
		// a는 사용자의 최신 성공 평가 1건이다(없으면 null). Credit 산출 · 정책 판정과 같은 순서로 고른다.
		String from = " from UserAccount u left join UserCredit c on c.userId = u.userId"
				+ " left join RiskAssessment a on a.user.userId = u.userId" + where
				+ " and (a.assessmentId is null or not exists (select newer.assessmentId from RiskAssessment newer"
				+ " where newer.user.userId = u.userId and (newer.assessedAt > a.assessedAt"
				+ " or (newer.assessedAt = a.assessedAt and newer.assessmentId > a.assessmentId))))";
		var count = bind(em.createQuery("select count(u)" + from, Long.class), parameters);
		if (services != null) ServiceScopeQuery.bind(count, services);
		long total = count.getSingleResult();
		var query = em.createQuery("select new com.bts.fairticket.user.dto.UserResponse("
				+ "u.userId, u.serviceId, u.externalUserId, u.accountStatus, u.createdAt, c.creditScore, c.tier)"
				+ from + " order by " + page.orderBy(), UserResponse.class);
		bind(query, parameters);
		if (services != null) ServiceScopeQuery.bind(query, services);
		var content = query.setFirstResult(page.offset()).setMaxResults(page.size()).getResultList();
		if (content.stream().anyMatch(user -> user.creditScore() == null)) {
			throw new ApiException(ResponseCode.INTERNAL_SERVER_ERROR);
		}
		return PageResponse.of(content, page.page(), page.size(), total);
	}

	private void add(StringBuilder where, Map<String, Object> parameters, String name, String condition, Object value) {
		if (value != null) { where.append(" and ").append(condition).append(" :").append(name); parameters.put(name, value); }
	}
	private <T extends Query> T bind(T query, Map<String, Object> parameters) {
		parameters.forEach(query::setParameter); return query;
	}
}
