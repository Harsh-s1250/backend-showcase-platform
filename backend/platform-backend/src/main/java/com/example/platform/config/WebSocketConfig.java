package com.example.platform.config;

import com.example.platform.ws.TerminalWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private static final Pattern PROJECT_ID_PATTERN = Pattern.compile("/api/projects/([^/]+)/terminal$");

    private final TerminalWebSocketHandler terminalWebSocketHandler;

    public WebSocketConfig(TerminalWebSocketHandler terminalWebSocketHandler) {
        this.terminalWebSocketHandler = terminalWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalWebSocketHandler, "/api/projects/*/terminal")
                .addInterceptors(new ProjectIdHandshakeInterceptor())
                .setAllowedOrigins("*"); // public endpoint, same as the rest of the showcase experience
    }

    // PRD §28 "Maximum WebSocket/message size" — capped at the container level since this
    // is the platform's only WebSocket endpoint. Terminal input is keystrokes, not bulk
    // data, so a small limit is intentional, not a bug.
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(8192);
        container.setMaxSessionIdleTimeout(30L * 60 * 1000); // 30 min idle disconnect
        return container;
    }

    private static class ProjectIdHandshakeInterceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                        WebSocketHandler wsHandler, Map<String, Object> attributes) {
            Matcher matcher = PROJECT_ID_PATTERN.matcher(request.getURI().getPath());
            if (!matcher.find()) return false;
            try {
                attributes.put("projectId", UUID.fromString(matcher.group(1)));
                return true;
            } catch (IllegalArgumentException e) {
                return false; // not a valid UUID — reject the handshake
            }
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Exception exception) {}
    }
}
