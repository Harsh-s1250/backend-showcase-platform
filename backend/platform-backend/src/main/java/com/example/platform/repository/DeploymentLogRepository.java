package com.example.platform.repository;

import com.example.platform.entity.DeploymentLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeploymentLogRepository extends JpaRepository<DeploymentLog, UUID> {
    List<DeploymentLog> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}