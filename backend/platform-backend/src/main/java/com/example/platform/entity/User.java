package com.example.platform.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "github_id", nullable = false, unique = true)
    private Long githubId;

    @Column(name = "github_username", nullable = false)
    private String githubUsername;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "access_token", nullable = false)
    private String accessToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected User() {} // JPA

    public User(Long githubId, String githubUsername, String avatarUrl, String accessToken) {
        this.githubId = githubId;
        this.githubUsername = githubUsername;
        this.avatarUrl = avatarUrl;
        this.accessToken = accessToken;
    }

    public UUID getId() { return id; }
    public Long getGithubId() { return githubId; }
    public String getGithubUsername() { return githubUsername; }
    public String getAvatarUrl() { return avatarUrl; }
    public String getAccessToken() { return accessToken; }

    public void updateAccessToken(String accessToken) {
        this.accessToken = accessToken;
        this.updatedAt = Instant.now();
    }
}