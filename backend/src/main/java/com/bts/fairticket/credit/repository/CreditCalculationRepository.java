package com.bts.fairticket.credit.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bts.fairticket.credit.entity.CreditCalculation;

public interface CreditCalculationRepository extends JpaRepository<CreditCalculation, Long> {

	List<CreditCalculation> findByHistoryIdIn(Collection<Long> historyIds);
}
