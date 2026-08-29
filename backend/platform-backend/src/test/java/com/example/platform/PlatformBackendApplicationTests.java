package com.example.platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// WebEnvironment.RANDOM_PORT, not the default MOCK — WebSocketConfig registers a real
// ServletServerContainerFactoryBean (needed for the terminal WebSocket endpoint), which
// requires an actual embedded servlet container to expose the JSR-356
// jakarta.websocket.server.ServerContainer attribute. The default mock web environment never
// bootstraps that, so this context load would otherwise fail with "Attribute
// 'jakarta.websocket.server.ServerContainer' not found in ServletContext" — not a real bug in
// WebSocketConfig, just this test never having been run against the real bean set before now.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
