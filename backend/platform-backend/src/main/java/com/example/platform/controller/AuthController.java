package com.example.platform.controller;

import com.example.platform.entity.User;
import com.example.platform.repository.UserRepository;
import com.example.platform.service.GitHubOAuthService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@RestController
public class AuthController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final GitHubOAuthService gitHubOAuthService;
    private final UserRepository userRepository;

    // Server-controlled only (never taken from a request param) so this can't become an
    // open-redirect vector. Defaults to the app root, which is where the built frontend is
    // served from in production. Override in application-local.properties during `npm run dev`
    // (e.g. app.oauth.post-login-redirect=http://localhost:5173/) since the OAuth callback is a
    // full-page redirect that lands on the backend's own port (8090), not the Vite dev server.
    @Value("${app.oauth.post-login-redirect:/}")
    private String postLoginRedirect;

    public AuthController(GitHubOAuthService gitHubOAuthService, UserRepository userRepository) {
        this.gitHubOAuthService = gitHubOAuthService;
        this.userRepository = userRepository;
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

        String separator = postLoginRedirect.contains("?") ? "&" : "?";
        response.sendRedirect(postLoginRedirect + separator + "loggedIn=true");
    }

    @GetMapping("/auth/me")
    public Object me(HttpSession session) {
        Object userIdObj = session.getAttribute("userId");
        if (userIdObj == null) {
            return java.util.Map.of("authenticated", false);
        }

        // Look the user up rather than trusting the session blindly — if the account was
        // deleted from under an active session, report logged-out instead of throwing, since
        // this endpoint exists for the frontend to probe quietly on every page load.
        UUID userId = (UUID) userIdObj;
        return userRepository.findById(userId)
                .<Object>map(user -> java.util.Map.of(
                        "authenticated", true,
                        "userId", user.getId().toString(),
                        "githubUsername", user.getGithubUsername(),
                        "avatarUrl", user.getAvatarUrl() != null ? user.getAvatarUrl() : ""
                ))
                .orElseGet(() -> java.util.Map.of("authenticated", false));
    }

    @PostMapping("/auth/logout")
    public Object logout(HttpSession session) {
        session.invalidate();
        return java.util.Map.of("authenticated", false);
    }
}