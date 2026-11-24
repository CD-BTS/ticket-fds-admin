package com.bts.fairticket.policy.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bts.fairticket.policy.entity.PolicyApplication;

public interface PolicyApplicationRepository extends JpaRepository<PolicyApplication, Long> {

	Optional<PolicyApplication> findByServiceIdAndExternalApplicationId(String serviceId, String externalApplicationId);
}
