package com.example.platform.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deployment_logs")
public class DeploymentLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "log_type", nullable = false)
    private String logType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    private Boolean success;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected DeploymentLog() {} // JPA

    public DeploymentLog(UUID projectId, String logType, String content, Boolean success) {
        this.projectId = projectId;
        this.logType = logType;
        this.content = content;
        this.success = success;
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public String getLogType() { return logType; }
    public String getContent() { return content; }
    public Boolean getSuccess() { return success; }
    public Instant getCreatedAt() { return createdAt; }
}