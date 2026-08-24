package com.example.platform.analyzer;

/**
 * Result of classifying a repository into a {@link ProjectType}.
 *
 * Deliberately carries all three pieces the PRD asks for (§6): the type itself,
 * how confident we are in it, and a human-readable reason — never just a bare
 * enum value. A caller should never have to guess why a classification was made.
 */
public record ProjectTypeDetection(ProjectType projectType, DetectionStatus status, String reason) {

    public static ProjectTypeDetection detected(ProjectType type, String reason) {
        return new ProjectTypeDetection(type, DetectionStatus.DETECTED, reason);
    }

    public static ProjectTypeDetection inferred(ProjectType type, String reason) {
        return new ProjectTypeDetection(type, DetectionStatus.INFERRED, reason);
    }

    public static ProjectTypeDetection unknown(String reason) {
        return new ProjectTypeDetection(ProjectType.UNKNOWN, DetectionStatus.UNKNOWN, reason);
    }
}
