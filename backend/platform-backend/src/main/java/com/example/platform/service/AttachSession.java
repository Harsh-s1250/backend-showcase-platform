package com.example.platform.service;

import java.io.Closeable;

/**
 * What TerminalSessionManager actually needs from a live attach session: send input, and be
 * closeable. Extracted so TerminalSessionManager (and its tests) don't have to depend on
 * ConsoleAttachSession's concrete constructor, which makes a real blocking socket connection
 * to the Docker daemon as a side effect of construction — fine in production, but it means a
 * test double can't reasonably extend ConsoleAttachSession itself. Implemented by
 * ConsoleAttachSession in production and by a lightweight fake in TerminalSessionManagerTest.
 */
public interface AttachSession extends Closeable {
    void sendInput(String text);
}
