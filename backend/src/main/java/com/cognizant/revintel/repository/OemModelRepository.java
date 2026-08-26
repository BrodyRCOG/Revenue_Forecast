package com.cognizant.revintel.repository;

import com.cognizant.revintel.entity.OemModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OemModelRepository extends JpaRepository<OemModel, String> {

    List<OemModel> findByRealAnchorTrue();

    long countByRealAnchorTrue();
}
