package com.bts.fairticket.credit.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bts.fairticket.common.exception.ApiException;
import com.bts.fairticket.common.response.ResponseCode;
import com.bts.fairticket.credit.repository.CreditHistoryRepository;
import com.bts.fairticket.credit.repository.UserCreditRepository;
import com.bts.fairticket.user.repository.UserAccountRepository;

@ExtendWith(MockitoExtension.class)
class CreditQueryServiceTest {

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private UserCreditRepository userCreditRepository;

    @Mock
    private CreditHistoryRepository creditHistoryRepository;

    @InjectMocks
    private CreditQueryService creditQueryService;

    @Test
    @DisplayName("사용자는 존재하지만 Credit 정보가 누락된 경우 CREDIT_NOT_FOUND 예외 발생")
    void getCredit_CreditNotFound() {
        given(userAccountRepository.findById("bts_user_001")).willReturn(Optional.of(
                new com.bts.fairticket.user.entity.UserAccount("bts_user_001", "service-1", "ext-1")));
        given(userCreditRepository.findById("bts_user_001")).willReturn(Optional.empty());

        assertThatThrownBy(() -> creditQueryService.getCredit("bts_user_001", "service-1"))
                .isInstanceOf(ApiException.class)
                .extracting("responseCode")
                .isEqualTo(ResponseCode.CREDIT_NOT_FOUND);
    }

    @Test
    @DisplayName("조회 시작일이 종료일보다 늦은 경우 INVALID_QUERY_PARAMETER 예외 발생")
    void getCreditHistory_InvalidDateRange() {
        given(userAccountRepository.findById("bts_user_001")).willReturn(Optional.of(
                new com.bts.fairticket.user.entity.UserAccount("bts_user_001", "service-1", "ext-1")));

        assertThatThrownBy(() -> creditQueryService.getCreditHistory(
                "bts_user_001", "service-1", 0, 20, "2026-10-15", "2026-10-10"))
                .isInstanceOf(ApiException.class)
                .extracting("responseCode")
                .isEqualTo(ResponseCode.INVALID_QUERY_PARAMETER);
    }

    @Test
    @DisplayName("size가 100을 초과하면 INVALID_QUERY_PARAMETER 예외 발생")
    void getCreditHistory_SizeExceeded() {
        given(userAccountRepository.findById("bts_user_001")).willReturn(Optional.of(
                new com.bts.fairticket.user.entity.UserAccount("bts_user_001", "service-1", "ext-1")));

        assertThatThrownBy(() -> creditQueryService.getCreditHistory(
                "bts_user_001", "service-1", 0, 101, null, null))
                .isInstanceOf(ApiException.class)
                .extracting("responseCode")
                .isEqualTo(ResponseCode.INVALID_QUERY_PARAMETER);
    }
    @Test
    @DisplayName("[#67] 다른 서비스의 사용자는 Credit과 이력 모두 USER_NOT_FOUND로 숨긴다")
    void getCredit_OtherServiceUserIsHidden() {
        given(userAccountRepository.findById("bts_user_001")).willReturn(Optional.of(
                new com.bts.fairticket.user.entity.UserAccount("bts_user_001", "service-1", "ext-1")));

        assertThatThrownBy(() -> creditQueryService.getCredit("bts_user_001", "service-2"))
                .isInstanceOf(ApiException.class).extracting("responseCode").isEqualTo(ResponseCode.USER_NOT_FOUND);
        assertThatThrownBy(() -> creditQueryService.getCreditHistory("bts_user_001", "service-2", 0, 20, null, null))
                .isInstanceOf(ApiException.class).extracting("responseCode").isEqualTo(ResponseCode.USER_NOT_FOUND);
        org.mockito.Mockito.verifyNoInteractions(userCreditRepository, creditHistoryRepository);
    }
}
