package com.example.platform.analyzer;

/**
 * The kind of interactive experience this project can be given automatically.
 * Kept intentionally small for MVP v2 — see PRD §5 "Supported Project Types".
 */
public enum ProjectType {
    REST_APPLICATION,
    CONSOLE_APPLICATION,
    UNSUPPORTED,
    UNKNOWN
}
