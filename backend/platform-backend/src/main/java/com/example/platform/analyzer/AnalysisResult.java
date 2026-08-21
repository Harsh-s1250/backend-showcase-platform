package com.example.platform.analyzer;

public record AnalysisResult(
        Detected<String> buildTool,
        Detected<String> framework,
        Detected<String> javaVersion,
        Detected<Boolean> openApiAvailable,
        Detected<Boolean> dockerPresent,
        Detected<String> databaseDriver
) {}