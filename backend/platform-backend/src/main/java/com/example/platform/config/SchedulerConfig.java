package com.example.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Deliberately its own class, not a @Bean method inside WebSocketConfig: WebSocketConfig's
 * constructor depends on TerminalWebSocketHandler, which depends on this scheduler — if the
 * scheduler bean lived inside WebSocketConfig, Spring would need to fully construct
 * WebSocketConfig (to invoke its @Bean method) before it could finish constructing
 * WebSocketConfig's own constructor argument, which is exactly the cycle Spring rejects.
 */
@Configuration
public class SchedulerConfig {

    @Bean
    public ScheduledExecutorService terminalSessionScheduler() {
        return Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "terminal-session-timeout");
            t.setDaemon(true);
            return t;
        });
    }
}
