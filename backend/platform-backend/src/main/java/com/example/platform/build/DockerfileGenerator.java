package com.example.platform.build;

public class DockerfileGenerator {

    /**
     * @param buildTool      "Maven", "Gradle", or "Plain Java" — as detected by AnalyzerService.
     *                       Falls back to Maven behavior for any other/unrecognized value, since
     *                       that was this generator's only supported path historically.
     * @param javaVersion    detected Java version, or null/blank to fall back to "17".
     * @param mainClass      detected main class (FQCN), or null if none was found/configured.
     * @param isConsoleApp   true when the project was classified CONSOLE_APPLICATION.
     * @param isSpringBoot   true when the analyzer detected the Spring Boot framework.
     * @param databaseDriver "MySQL", "PostgreSQL", or null — only consulted for the Plain Java
     *                       path, where there's no build tool to resolve the JDBC driver as a
     *                       dependency, so this generator has to fetch the jar itself.
     */
    public static String generate(String buildTool, String javaVersion, String mainClass,
                                    boolean isConsoleApp, boolean isSpringBoot, String databaseDriver) {
        String resolvedJavaVersion = (javaVersion != null && !javaVersion.isBlank())
                ? javaVersion
                : "17";

        // Only patch the jar's manifest ourselves for a NON-Spring-Boot console app. A Spring
        // Boot jar's manifest Main-Class must stay org.springframework.boot.loader.launch.JarLauncher
        // (that's what makes the fat jar's nested BOOT-INF classpath work at all) — overwriting it
        // would break a perfectly working Spring Boot console app (e.g. a CommandLineRunner reading
        // stdin). For everything else, this directly fixes the handoff's documented failure mode:
        // "a pom.xml with no entrypoint configured will build successfully but fail at runtime with
        // 'no main manifest attribute'." Re-applying the same main class when one is already
        // correctly configured is harmless (idempotent), so we don't need to know whether the build
        // file already configured it — just whether we found *a* main class at all.
        boolean shouldPatchManifest = isConsoleApp && !isSpringBoot && mainClass != null && !mainClass.isBlank();

        String normalizedBuildTool = buildTool == null ? "Maven" : buildTool;

        return switch (normalizedBuildTool) {
            case "Gradle" -> generateGradle(resolvedJavaVersion, mainClass, shouldPatchManifest);
            case "Plain Java" -> generatePlainJava(resolvedJavaVersion, mainClass, databaseDriver);
            default -> generateMaven(resolvedJavaVersion, mainClass, shouldPatchManifest);
        };
    }

    private static String generateMaven(String javaVersion, String mainClass, boolean patchManifest) {
        String manifestPatchStep = patchManifest
                ? "RUN jar --update --file target/*.jar --main-class " + mainClass + "\n"
                : "";

        return """
                FROM maven:3.9-eclipse-temurin-%s AS build
                WORKDIR /app
                COPY pom.xml .
                RUN mvn -B dependency:go-offline
                COPY src ./src
                RUN mvn -B clean package -DskipTests
                %s
                FROM eclipse-temurin:%s-jre-alpine
                WORKDIR /app
                COPY --from=build /app/target/*.jar app.jar
                EXPOSE 8080
                ENTRYPOINT ["java", "-jar", "app.jar"]
                """.formatted(javaVersion, manifestPatchStep, javaVersion);
    }

    private static String generateGradle(String javaVersion, String mainClass, boolean patchManifest) {
        String manifestPatchStep = patchManifest
                ? "RUN jar --update --file build/libs/*.jar --main-class " + mainClass + "\n"
                : "";

        // Uses the repo's own Gradle wrapper (gradlew) rather than assuming a global `gradle`
        // binary — the wrapper pins the exact Gradle version the project was written against,
        // which the base image can't guarantee. Requires gradlew/gradle-wrapper.jar to be
        // committed to the repo, which is the near-universal convention for Gradle projects.
        return """
                FROM eclipse-temurin:%s-jdk-alpine AS build
                WORKDIR /app
                COPY . .
                RUN chmod +x ./gradlew && ./gradlew build -x test --no-daemon
                %s
                FROM eclipse-temurin:%s-jre-alpine
                WORKDIR /app
                COPY --from=build /app/build/libs/*.jar app.jar
                EXPOSE 8080
                ENTRYPOINT ["java", "-jar", "app.jar"]
                """.formatted(javaVersion, manifestPatchStep, javaVersion);
    }

    // Pinned versions for the JDBC driver jars fetched directly for the Plain Java path (no build
    // tool means no dependency resolution otherwise). Not verified against a live Maven Central
    // fetch at the time this was written — confirm these coordinates still resolve if a
    // plain-Java build fails at this step.
    private static final String MYSQL_DRIVER_URL =
            "https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar";
    private static final String POSTGRESQL_DRIVER_URL =
            "https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar";

    /**
     * No build tool at all: compiles every .java file directly with javac and runs the detected
     * main class by name (there's no jar, so no manifest to rely on). Requires mainClass to be
     * non-null — callers must check this before invoking (BuildService throws a clear error
     * instead of generating a broken Dockerfile when it's missing/ambiguous).
     *
     * When databaseDriver is "MySQL" or "PostgreSQL" (detected from a "jdbc:mysql"/"jdbc:postgresql"
     * string literal in source — see AnalyzerService.detectDatabaseDriverFromSource), the matching
     * JDBC driver jar is fetched directly and put on both the javac and java classpaths, since
     * there's no pom.xml/build.gradle to declare it as a dependency otherwise.
     */
    private static String generatePlainJava(String javaVersion, String mainClass, String databaseDriver) {
        if (mainClass == null || mainClass.isBlank()) {
            throw new IllegalStateException(
                    "Cannot generate a Dockerfile for a plain Java project without a known main class. " +
                            "Either exactly one class with main() must exist, or an entrypoint must be configured explicitly.");
        }

        String driverFetchStep = "";
        boolean needsDriverJar = "MySQL".equals(databaseDriver) || "PostgreSQL".equals(databaseDriver);

        if (needsDriverJar) {
            String driverUrl = "MySQL".equals(databaseDriver) ? MYSQL_DRIVER_URL : POSTGRESQL_DRIVER_URL;
            driverFetchStep = "RUN apk add --no-cache curl && curl -fsSL -o driver.jar " + driverUrl + "\n";
        }

        // The compile step includes the driver jar on the classpath too (via CLASSPATH env, since
        // javac's @sources.txt file list doesn't leave room for extra -cp flags cleanly): needed
        // whenever the app's own code references JDBC driver classes directly (uncommon — most
        // JDBC code only needs java.sql.* — but harmless to include either way).
        String compileClasspathEnv = needsDriverJar ? "ENV CLASSPATH=driver.jar\n" : "";

        return """
                FROM eclipse-temurin:%s-jdk-alpine AS build
                WORKDIR /app
                COPY . .
                %s%s
                RUN find . -name "*.java" > /tmp/sources.txt && javac -d out @/tmp/sources.txt

                FROM eclipse-temurin:%s-jre-alpine
                WORKDIR /app
                COPY --from=build /app/out .
                %s
                EXPOSE 8080
                ENTRYPOINT ["java", %s"%s"]
                """.formatted(
                        javaVersion,
                        driverFetchStep, compileClasspathEnv,
                        javaVersion,
                        needsDriverJar ? "COPY --from=build /app/driver.jar ." : "",
                        needsDriverJar ? "\"-cp\", \".:driver.jar\"," : "",
                        mainClass
                );
    }
}
