package com.cognizant.revintel.repository;

import com.cognizant.revintel.entity.Workforce;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.List;

public interface WorkforceRepository extends JpaRepository<Workforce, String> {

    /** Resource groups already running hot -- the input to the over-utilisation signal. */
    List<Workforce> findByCurrentUtilizationGreaterThan(BigDecimal threshold);
}
