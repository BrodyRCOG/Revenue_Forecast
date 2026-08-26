package com.cognizant.revintel.repository;

import com.cognizant.revintel.entity.Opportunity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OpportunityRepository extends JpaRepository<Opportunity, String> {

    /** Live pipeline: everything not yet closed. */
    @Query("select o from Opportunity o where o.status = 'Open' order by o.quarter, o.id")
    List<Opportunity> findOpenPipeline();

    /** Closed history (Won + Lost) -- the sample the Bayesian win rates are fitted on. */
    @Query("select o from Opportunity o where o.status in ('Won', 'Lost') order by o.quarter, o.id")
    List<Opportunity> findClosed();

    @Query("select o from Opportunity o where o.status = 'Won' order by o.quarter, o.id")
    List<Opportunity> findWon();
}
