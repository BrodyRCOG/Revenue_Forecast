package com.cognizant.revintel.repository;

import com.cognizant.revintel.entity.ProjectBilling;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectBillingRepository extends JpaRepository<ProjectBilling, String> {

    List<ProjectBilling> findByResourceGroup(String resourceGroup);
}
