package com.example.platform.build;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps raw Docker build-log lines to short, human-friendly status messages for the dashboard's
 * default build view. One instance per build (it tracks how many FROM instructions it's seen, to
 * tell the build stage apart from the runtime stage in a multi-stage Dockerfile) — don't share
 * an instance across concurrent builds.
 *
 * This is a UI convenience layer only. The raw log is still captured and streamed unchanged
 * (see BuildService) — this never replaces it, just adds a friendlier parallel summary.
 */
public class BuildLogTranslator {

    private static final Pattern STEP_PATTERN = Pattern.compile("^Step \\d+/\\d+ : (.+)$");

    private int fromCount = 0;

    /**
     * @return a friendly message for this raw line, or null if the line isn't a Dockerfile
     *         instruction boundary (e.g. compiler/dependency-download noise) or isn't one worth
     *         surfacing on its own. Callers should simply emit nothing for a null result — the
     *         raw line is still available in the full log.
     */
    public String translate(String rawLine) {
        if (rawLine == null) return null;
        Matcher matcher = STEP_PATTERN.matcher(rawLine.trim());
        if (!matcher.matches()) return null;

        String instruction = matcher.group(1).trim();

        if (instruction.startsWith("FROM")) {
            fromCount++;
            return fromCount == 1 ? "Setting up build environment…" : "Preparing runtime image…";
        }
        if (instruction.startsWith("COPY")) {
            return "Copying project files…";
        }
        if (instruction.contains("mvn")) {
            return "Compiling with Maven…";
        }
        if (instruction.contains("gradlew")) {
            return "Compiling with Gradle…";
        }
        if (instruction.contains("javac")) {
            return "Compiling Java source files…";
        }
        if (instruction.contains("curl") && instruction.contains("driver.jar")) {
            return "Fetching database driver…";
        }
        if (instruction.startsWith("RUN jar --update")) {
            return "Configuring application entrypoint…";
        }
        if (instruction.startsWith("ENTRYPOINT")) {
            return "Finalizing image…";
        }

        // WORKDIR / ENV / EXPOSE and anything else unrecognized — not interesting enough for a
        // friendly line of their own, still visible in the raw log toggle.
        return null;
    }
}
