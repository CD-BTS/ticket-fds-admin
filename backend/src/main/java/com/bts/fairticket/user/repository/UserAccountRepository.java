package com.bts.fairticket.user.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bts.fairticket.user.entity.UserAccount;

public interface UserAccountRepository extends JpaRepository<UserAccount, String> {

	Optional<UserAccount> findByServiceIdAndExternalUserId(String serviceId, String externalUserId);

	/**
	 * 한 사용자의 이벤트 등록을 순서대로 처리하기 위한 행 잠금 (#40).
	 * 같은 트랜잭션에서 user_credit도 잠근다면 반드시 user_account를 먼저 잠근다(잠금 순서: user_account → user_credit).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT u FROM UserAccount u WHERE u.userId = :userId")
	Optional<UserAccount> findByIdForUpdate(@Param("userId") String userId);
}
