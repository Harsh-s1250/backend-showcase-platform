package com.example.platform.service;

import com.example.platform.entity.User;
import com.example.platform.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class GitHubOAuthService {

    @Value("${github.oauth.client-id}")
    private String clientId;

    @Value("${github.oauth.client-secret}")
    private String clientSecret;

    private final UserRepository userRepository;
    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EncryptionService encryptionService;

    public GitHubOAuthService(UserRepository userRepository, EncryptionService encryptionService) {
        this.userRepository = userRepository;
        this.encryptionService = encryptionService;
    }

    public String buildAuthorizationUrl(String state) {
        return "https://github.com/login/oauth/authorize"
                + "?client_id=" + clientId
                + "&redirect_uri=http://localhost:8090/auth/callback"
                + "&scope=repo"
                + "&state=" + state;
    }

    public User handleCallback(String code) {
        String accessToken = exchangeCodeForToken(code);
        JsonNode profile = fetchGitHubProfile(accessToken);   // real token, talks to GitHub

        long githubId = profile.get("id").asLong();
        String username = profile.get("login").asText();
        String avatarUrl = profile.has("avatar_url") ? profile.get("avatar_url").asText() : null;

        String encryptedToken = encryptionService.encrypt(accessToken);   // encrypted only for storage

        return userRepository.findByGithubId(githubId)
                .map(existing -> {
                    existing.updateAccessToken(encryptedToken);
                    return userRepository.save(existing);
                })
                .orElseGet(() -> userRepository.save(
                        new User(githubId, username, avatarUrl, encryptedToken)));
    }

    private String exchangeCodeForToken(String code) {
        Map<String, Object> response = restClient.post()
                .uri("https://github.com/login/oauth/access_token")
                .header("Accept", "application/json")
                .body(Map.of(
                        "client_id", clientId,
                        "client_secret", clientSecret,
                        "code", code
                ))
                .retrieve()
                .body(Map.class);

        if (response == null || !response.containsKey("access_token")) {
            throw new IllegalStateException("GitHub token exchange failed: " + response);
        }
        return (String) response.get("access_token");
    }

    private JsonNode fetchGitHubProfile(String encryptedToken) {
        String body = restClient.get()
                .uri("https://api.github.com/user")
                .header("Authorization", "Bearer " + encryptedToken)
                .header("Accept", "application/vnd.github+json")
                .retrieve()
                .body(String.class);
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse GitHub profile response", e);
        }
    }
}