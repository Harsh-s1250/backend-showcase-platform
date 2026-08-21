package com.example.platform.controller;

import com.example.platform.entity.User;
import com.example.platform.service.GitHubOAuthService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

@RestController
public class AuthController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final GitHubOAuthService gitHubOAuthService;

    public AuthController(GitHubOAuthService gitHubOAuthService) {
        this.gitHubOAuthService = gitHubOAuthService;
    }

    private String generateState() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @GetMapping("/auth/login")
    public void login(HttpSession session, HttpServletResponse response) throws IOException {
        String state = generateState();
        session.setAttribute("oauth_state", state);

        response.sendRedirect(gitHubOAuthService.buildAuthorizationUrl(state));
    }

    @GetMapping("/auth/callback")
    public void callback(@RequestParam String code,
                         @RequestParam String state,
                         HttpSession session,
                         HttpServletResponse response) throws IOException {

        String expectedState = (String) session.getAttribute("oauth_state");
        if (expectedState == null || !expectedState.equals(state)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid OAuth state — possible CSRF attempt");
            return;
        }
        session.removeAttribute("oauth_state");

        User user = gitHubOAuthService.handleCallback(code);
        session.setAttribute("userId", user.getId());

        response.sendRedirect("/explorer.html?loggedIn=true");
    }

    @GetMapping("/auth/me")
    public Object me(HttpSession session) {
        Object userId = session.getAttribute("userId");
        if (userId == null) {
            return java.util.Map.of("authenticated", false);
        }
        return java.util.Map.of("authenticated", true, "userId", userId.toString());
    }
}