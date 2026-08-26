package com.cognizant.revintel.repository;

import com.cognizant.revintel.entity.Contract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ContractRepository extends JpaRepository<Contract, String> {

    /** Contracts graded High or Critical renewal risk -- the input to the at-risk-ARR signal. */
    @Query("select c from Contract c where c.renewalRisk in ('High', 'Critical')")
    List<Contract> findAtRisk();
}
