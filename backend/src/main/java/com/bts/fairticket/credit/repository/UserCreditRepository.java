package com.bts.fairticket.credit.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bts.fairticket.credit.entity.UserCredit;

public interface UserCreditRepository extends JpaRepository<UserCredit, String> {

    // 한 사용자의 관람 이력 변경과 Credit 갱신을 순서대로 처리하기 위한 행 잠금
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM UserCredit c WHERE c.userId = :userId")
    Optional<UserCredit> findByIdForUpdate(@Param("userId") String userId);
}
