package com.example.platform.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;

@Entity
@Table(name = "projects")
public class Project {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "github_repo_url", nullable = false)
    private String githubRepoUrl;

    @Column(nullable = false)
    private String branch = "main";

    @Column(nullable = false)
    private String status = "CREATED";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "clone_path")
    private String clonePath;

    @Column(name = "detected_build_tool")
    private String detectedBuildTool;

    @Column(name = "detected_java_version")
    private String detectedJavaVersion;

    @Column(name = "docker_image_id")
    private String dockerImageId;

    @Column(name = "container_id")
    private String containerId;

    @Column(name = "host_port")
    private Integer hostPort;

    @Column(name = "db_name")
    private String dbName;

    @Column(name = "db_username")
    private String dbUsername;

    @Column(name = "db_password")
    private String dbPassword;

    @ManyToOne
    @JoinColumn(name = "owner_id")
    private User owner;

    protected Project() {} // JPA

    public Project(String name, String githubRepoUrl, String branch) {
        this.name = name;
        this.githubRepoUrl = githubRepoUrl;
        this.branch = (branch != null) ? branch : "main";
    }

    // Getters (no setters for createdAt — immutable after creation)
    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getGithubRepoUrl() { return githubRepoUrl; }
    public String getBranch() { return branch; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public String getClonePath() { return clonePath; }
    public void setClonePath(String clonePath) {
        this.clonePath = clonePath;
        this.updatedAt = Instant.now();
    }

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public String getDetectedBuildTool() { return detectedBuildTool; }
    public String getDetectedJavaVersion() { return detectedJavaVersion; }

    public void setDetectedBuildTool(String detectedBuildTool) {
        this.detectedBuildTool = detectedBuildTool;
        this.updatedAt = Instant.now();
    }
    public void setDetectedJavaVersion(String detectedJavaVersion) {
        this.detectedJavaVersion = detectedJavaVersion;
        this.updatedAt = Instant.now();
    }

    public String getDockerImageId() { return dockerImageId; }
    public void setDockerImageId(String dockerImageId) {
        this.dockerImageId = dockerImageId;
        this.updatedAt = Instant.now();
    }

    public String getContainerId() { return containerId; }
    public Integer getHostPort() { return hostPort; }

    public void setContainerId(String containerId) {
        this.containerId = containerId;
        this.updatedAt = Instant.now();
    }
    public void setHostPort(Integer hostPort) {
        this.hostPort = hostPort;
        this.updatedAt = Instant.now();
    }

    public String getDbName() { return dbName; }
    public String getDbUsername() { return dbUsername; }
    public String getDbPassword() { return dbPassword; }

    public void setDbCredentials(String dbName, String dbUsername, String dbPassword) {
        this.dbName = dbName;
        this.dbUsername = dbUsername;
        this.dbPassword = dbPassword;
        this.updatedAt = Instant.now();
    }

    public User getOwner() { return owner; }
    public void setOwner(User owner) {
        this.owner = owner;
        this.updatedAt = Instant.now();
    }
}