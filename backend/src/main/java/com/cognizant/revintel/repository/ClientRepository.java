package com.cognizant.revintel.repository;

import com.cognizant.revintel.entity.Client;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClientRepository extends JpaRepository<Client, String> {

    List<Client> findBySegment(String segment);
}
