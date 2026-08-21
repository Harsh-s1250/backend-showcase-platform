package com.example.platform.service;

import com.example.platform.entity.User;
import com.example.platform.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class CurrentUserService {

    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User requireCurrentUser(HttpSession session) {
        Object userIdObj = session.getAttribute("userId");
        if (userIdObj == null) {
            throw new SecurityException("Not authenticated. Log in via /auth/login first.");
        }
        UUID userId = (UUID) userIdObj;
        return userRepository.findById(userId)
                .orElseThrow(() -> new SecurityException("Session user no longer exists."));
    }

    public void requireOwnership(User currentUser, UUID projectOwnerId) {
        if (projectOwnerId == null || !projectOwnerId.equals(currentUser.getId())) {
            throw new SecurityException("You do not have access to this project.");
        }
    }
}