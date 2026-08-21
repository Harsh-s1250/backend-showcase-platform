package com.example.platform.analyzer;

public record Detected<T>(T value, DetectionStatus status) {

    public static <T> Detected<T> detected(T value) {
        return new Detected<>(value, DetectionStatus.DETECTED);
    }

    public static <T> Detected<T> inferred(T value) {
        return new Detected<>(value, DetectionStatus.INFERRED);
    }

    public static <T> Detected<T> unknown() {
        return new Detected<>(null, DetectionStatus.UNKNOWN);
    }
}