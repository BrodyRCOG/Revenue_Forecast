package com.cognizant.revintel.repository;

import com.cognizant.revintel.entity.Target;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TargetRepository extends JpaRepository<Target, String> {

    List<Target> findByQuarter(String quarter);

    List<Target> findByPractice(String practice);
}
