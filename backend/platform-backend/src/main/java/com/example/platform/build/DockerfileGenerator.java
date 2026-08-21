package com.example.platform.build;

public class DockerfileGenerator {

    public static String generate(String javaVersion) {
        // Fall back to a safe default if the analyzer couldn't determine a version —
        // never let an UNKNOWN/null value flow silently into the generated file.
        String resolvedJavaVersion = (javaVersion != null && !javaVersion.isBlank())
                ? javaVersion
                : "17";

        return """
                FROM maven:3.9-eclipse-temurin-%s AS build
                WORKDIR /app
                COPY pom.xml .
                RUN mvn -B dependency:go-offline
                COPY src ./src
                RUN mvn -B clean package -DskipTests

                FROM eclipse-temurin:%s-jre-alpine
                WORKDIR /app
                COPY --from=build /app/target/*.jar app.jar
                EXPOSE 8080
                ENTRYPOINT ["java", "-jar", "app.jar"]
                """.formatted(resolvedJavaVersion, resolvedJavaVersion);
    }
}