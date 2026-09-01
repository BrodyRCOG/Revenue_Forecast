package com.cognizant.revintel.repository;

import com.cognizant.revintel.entity.Contract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ContractRepository extends JpaRepository<Contract, String> {

    /** Contracts graded High or Critical renewal risk -- the input to the at-risk-ARR signal. */
    @Query("select c from Contract c where c.renewalRisk in ('High', 'Critical')")
    List<Contract> findAtRisk();

    /**
     * Contracts whose term ends within the window {@code [start, end]}, most imminent first -- the
     * input to the Manager Overview renewal watch. The window bounds are supplied by the caller so
     * "now" stays with {@link com.cognizant.revintel.service.AsOfProvider}, the single source of it.
     */
    @Query("select c from Contract c where c.endDate is not null "
            + "and c.endDate >= :start and c.endDate <= :end order by c.endDate")
    List<Contract> findEndingBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);
}
