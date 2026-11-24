package com.bts.fairticket.policy.service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.bts.fairticket.common.exception.ApiException;
import com.bts.fairticket.common.response.ResponseCode;
import com.bts.fairticket.policy.dto.PolicyApplicationRequest;
import com.bts.fairticket.policy.dto.PolicyApplicationResponse;
import com.bts.fairticket.policy.entity.PolicyApplication;
import com.bts.fairticket.policy.entity.PolicyDecision;
import com.bts.fairticket.policy.repository.PolicyApplicationRepository;
import com.bts.fairticket.policy.repository.PolicyDecisionRepository;
import com.bts.fairticket.user.repository.UserAccountRepository;

/**
 * 외부 티켓팅 서비스가 실제로 적용한 정책을 기록한다 (#67).
 * <ul>
 *   <li>정책을 결정한 것(policy_decision)과 적용한 것(policy_application)을 구분한다. ALLOW는 제재가 아니므로 받지 않는다.</li>
 *   <li>인증된 서비스, 사용자, 정책 결정, 공연이 서로 맞아야 한다. 다른 서비스의 사용자나 결정은 참조할 수 없다.</li>
 *   <li>(serviceId, externalApplicationId)가 멱등 키다. 같은 내용의 재전송은 기존 기록을 돌려주고, 다른 내용이면 409다.</li>
 * </ul>
 * behavior_event에 쓰지 않으므로 AI 평가 대상 선정, Feature, Credit 산출에 영향을 주지 않는다.
 */
@Service
public class PolicyApplicationService {

	/** 정책별로 허용하는 적용 결과. */
	public static final Map<String, Set<String>> RESULTS_BY_ACTION = Map.of(
			"REQUIRE_CAPTCHA", Set.of("CHALLENGED", "PASSED", "FAILED"),
			"DELAY", Set.of("DELAYED"),
			"RESTRICT", Set.of("BLOCKED"));

	private final PolicyApplicationRepository applications;
	private final PolicyDecisionRepository decisions;
	private final UserAccountRepository users;
	private final TransactionTemplate newTransaction;

	public PolicyApplicationService(PolicyApplicationRepository applications, PolicyDecisionRepository decisions,
			UserAccountRepository users, PlatformTransactionManager transactions) {
		this.applications = applications;
		this.decisions = decisions;
		this.users = users;
		this.newTransaction = new TransactionTemplate(transactions);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public record Result(PolicyApplicationResponse application, boolean created) { }

	public Result record(PolicyApplicationRequest request, String authenticatedService) {
		if (authenticatedService == null || !authenticatedService.equals(request.serviceId())) {
			throw new ApiException(ResponseCode.FORBIDDEN);
		}
		Set<String> allowed = RESULTS_BY_ACTION.get(request.action());
		if (allowed == null || !allowed.contains(request.result())) {
			throw new ApiException(ResponseCode.INVALID_REQUEST);
		}
		LocalDateTime appliedAt = parseUtc(request.appliedAt());
		try {
			return newTransaction.execute(tx -> register(request, appliedAt));
		} catch (DataIntegrityViolationException exception) {
			// 같은 멱등 키의 동시 요청이 먼저 저장됐다. 저장된 기록과 비교해 재전송이면 그대로 돌려준다.
			return newTransaction.execute(tx -> existing(request, appliedAt))
					.orElseThrow(() -> new ApiException(ResponseCode.DATA_CONFLICT));
		}
	}

	private Result register(PolicyApplicationRequest request, LocalDateTime appliedAt) {
		Optional<Result> replay = existing(request, appliedAt);
		if (replay.isPresent()) {
			return replay.get();
		}
		// 다른 서비스의 사용자는 없는 사용자와 같은 응답으로 숨긴다.
		var user = users.findById(request.userId())
				.filter(account -> account.getServiceId().equals(request.serviceId()))
				.orElseThrow(() -> new ApiException(ResponseCode.USER_NOT_FOUND));
		PolicyDecision decision = decisions.findById(request.decisionId())
				.filter(found -> found.getUser().getUserId().equals(user.getUserId()))
				.filter(found -> found.getServiceId() == null || found.getServiceId().equals(request.serviceId()))
				.orElseThrow(() -> new ApiException(ResponseCode.RESOURCE_NOT_FOUND));
		// 결정된 조치와 다른 조치, 결정에 기록된 공연과 다른 공연은 그 결정의 적용 기록이 될 수 없다.
		if (!decision.getAction().equals(request.action())
				|| (decision.getConcertId() != null && !decision.getConcertId().equals(request.concertId()))) {
			throw new ApiException(ResponseCode.DATA_CONFLICT);
		}
		var saved = applications.saveAndFlush(new PolicyApplication(request.serviceId(), request.externalApplicationId(),
				user.getUserId(), decision.getDecisionId(), request.concertId(), request.action(), request.result(),
				appliedAt));
		return new Result(PolicyApplicationResponse.from(saved), true);
	}

	private Optional<Result> existing(PolicyApplicationRequest request, LocalDateTime appliedAt) {
		return applications.findByServiceIdAndExternalApplicationId(request.serviceId(), request.externalApplicationId())
				.map(saved -> {
					if (!saved.getUserId().equals(request.userId()) || !saved.getDecisionId().equals(request.decisionId())
							|| !saved.getConcertId().equals(request.concertId())
							|| !saved.getAction().equals(request.action()) || !saved.getResult().equals(request.result())
							|| !saved.getAppliedAt().equals(appliedAt)) {
						throw new ApiException(ResponseCode.DATA_CONFLICT);
					}
					return new Result(PolicyApplicationResponse.from(saved), false);
				});
	}

	// 저장 경계에서 UTC로 바꾼다. 오프셋이 없거나 밀리초보다 정밀한 값은 받지 않는다(applied_at은 DATETIME(3)).
	private static LocalDateTime parseUtc(String value) {
		try {
			LocalDateTime utc = OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
			if (utc.getYear() < 1000 || utc.getYear() > 9999 || utc.getNano() % 1_000_000 != 0) {
				throw new ApiException(ResponseCode.INVALID_REQUEST);
			}
			return utc;
		} catch (DateTimeParseException exception) {
			throw new ApiException(ResponseCode.INVALID_REQUEST);
		}
	}
}
