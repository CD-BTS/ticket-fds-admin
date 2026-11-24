package com.bts.fairticket.credit.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bts.fairticket.common.exception.ApiException;
import com.bts.fairticket.common.response.ResponseCode;
import com.bts.fairticket.credit.dto.CreditHistoryResponse;
import com.bts.fairticket.credit.dto.CreditResponse;
import com.bts.fairticket.credit.entity.CreditHistory;
import com.bts.fairticket.credit.entity.UserCredit;
import com.bts.fairticket.credit.repository.CreditHistoryRepository;
import com.bts.fairticket.credit.repository.UserCreditRepository;
import com.bts.fairticket.user.repository.UserAccountRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreditQueryService {

    private final UserAccountRepository userAccountRepository;
    private final UserCreditRepository userCreditRepository;
    private final CreditHistoryRepository creditHistoryRepository;

    /** @param authenticatedService 인증된 외부 서비스. 다른 서비스의 사용자는 없는 사용자와 같이 404다 (#67). */
    public CreditResponse getCredit(String userId, String authenticatedService) {
        validateUserInService(userId, authenticatedService);

        UserCredit userCredit = userCreditRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ResponseCode.CREDIT_NOT_FOUND));

        return CreditResponse.from(userCredit);
    }

    public CreditHistoryResponse getCreditHistory(String userId, String authenticatedService, int page, int size,
            String startDate, String endDate) {
        validateUserInService(userId, authenticatedService);
        return CreditHistoryResponse.of(userId, findHistory(userId, page, size, startDate, endDate));
    }

    /**
     * 이력 조회 본체. 접근 권한은 호출자가 먼저 확인한다(서비스 API는 소속 서비스, 관리자 API는 AdminScope).
     * startDate, endDate는 UTC 날짜(yyyy-MM-dd)이며 둘 다 그날을 포함한다.
     */
    public Page<CreditHistory> findHistory(String userId, int page, int size, String startDate, String endDate) {
        // 쿼리 파라미터 유효성 검증 (size 최대 100, page >= 0)
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(ResponseCode.INVALID_QUERY_PARAMETER);
        }

        LocalDateTime startDateTime = null;
        LocalDateTime endDateTime = null;

        try {
            if (startDate != null && !startDate.isBlank()) {
                startDateTime = LocalDate.parse(startDate).atStartOfDay();
            }
            if (endDate != null && !endDate.isBlank()) {
                endDateTime = LocalDate.parse(endDate).atTime(LocalTime.MAX);
            }
        } catch (DateTimeParseException e) {
            throw new ApiException(ResponseCode.INVALID_QUERY_PARAMETER);
        }

        // 시작일이 종료일보다 늦은 경우 에러
        if (startDateTime != null && endDateTime != null && startDateTime.isAfter(endDateTime)) {
            throw new ApiException(ResponseCode.INVALID_QUERY_PARAMETER);
        }

        Pageable pageable = PageRequest.of(page, size);
        return creditHistoryRepository.findByUserIdAndDateRange(userId, startDateTime, endDateTime, pageable);
    }

    private void validateUserInService(String userId, String authenticatedService) {
        if (userId == null || userId.isBlank()) {
            throw new ApiException(ResponseCode.INVALID_REQUEST);
        }
        userAccountRepository.findById(userId)
                .filter(user -> user.getServiceId().equals(authenticatedService))
                .orElseThrow(() -> new ApiException(ResponseCode.USER_NOT_FOUND));
    }
}
