package com.example.platform.service;

import com.example.platform.analyzer.AnalysisResult;
import com.example.platform.analyzer.DetectionStatus;
import com.example.platform.analyzer.ProjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises AnalyzerService through its real public entry point, analyze(String clonePath),
 * against real temp-directory "repos" — not by reflecting into its private classification
 * methods. This matches how BuildController/ProjectController actually call it in production
 * (a freshly cloned repo on disk), so a passing test here means the real code path works, not
 * just an isolated helper method.
 *
 * A minimal, valid pom.xml is reused across most fixtures below (see MINIMAL_POM) since these
 * tests are about REST-vs-console-vs-unsupported classification, not Maven parsing itself —
 * AnalyzerServiceTest deliberately keeps each fixture to the smallest set of files that
 * exercises one specific classification path.
 */
class AnalyzerServiceTest {

    private final AnalyzerService analyzer = new AnalyzerService();

    private static final String MINIMAL_POM = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-project</artifactId>
                <version>1.0.0</version>
            </project>
            """;

    // ---- helpers ---------------------------------------------------------

    private void writePom(Path repoRoot) throws IOException {
        Files.writeString(repoRoot.resolve("pom.xml"), MINIMAL_POM);
    }

    private void writeJavaFile(Path repoRoot, String relativePath, String content) throws IOException {
        Path file = repoRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // ---- REST classification ----------------------------------------------

    @Test
    void classifiesRestApplication_whenRestControllerAnnotationPresent() throws IOException {
        Path repoRoot = Files.createTempDirectory("analyzer-test-rest");
        writePom(repoRoot);
        writeJavaFile(repoRoot, "src/main/java/com/example/TaskController.java", """
                package com.example;

                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class TaskController {
                }
                """);

        AnalysisResult result = analyzer.analyze(repoRoot.toString());

        assertThat(result.projectType().projectType()).isEqualTo(ProjectType.REST_APPLICATION);
        assertThat(result.projectType().status()).isEqualTo(DetectionStatus.DETECTED);
    }

    // ---- Console classification ----------------------------------------------

    @Test
    void classifiesConsoleApplication_whenMainMethodAndScannerPresent() throws IOException {
        Path repoRoot = Files.createTempDirectory("analyzer-test-console");
        writePom(repoRoot);
        writeJavaFile(repoRoot, "src/main/java/com/example/App.java", """
                package com.example;

                import java.util.Scanner;

                public class App {
                    public static void main(String[] args) {
                        Scanner sc = new Scanner(System.in);
                        System.out.println(sc.nextLine());
                    }
                }
                """);

        AnalysisResult result = analyzer.analyze(repoRoot.toString());

        assertThat(result.projectType().projectType()).isEqualTo(ProjectType.CONSOLE_APPLICATION);
        assertThat(result.projectType().status()).isEqualTo(DetectionStatus.DETECTED);
    }

    // ---- Unsupported: has a main(), but neither REST nor console signals ----

    @Test
    void classifiesUnsupported_whenMainMethodPresentButNoRestOrConsoleSignal() throws IOException {
        Path repoRoot = Files.createTempDirectory("analyzer-test-unsupported");
        writePom(repoRoot);
        writeJavaFile(repoRoot, "src/main/java/com/example/BatchJob.java", """
                package com.example;

                public class BatchJob {
                    public static void main(String[] args) {
                        System.out.println("processed " + args.length + " args");
                    }
                }
                """);

        AnalysisResult result = analyzer.analyze(repoRoot.toString());

        assertThat(result.projectType().projectType()).isEqualTo(ProjectType.UNSUPPORTED);
        assertThat(result.projectType().status()).isEqualTo(DetectionStatus.INFERRED);
    }

    // ---- Unknown: no main(), no REST controller at all ----

    @Test
    void classifiesUnknown_whenNoEntrypointOrRestSignalExists() throws IOException {
        Path repoRoot = Files.createTempDirectory("analyzer-test-unknown");
        writePom(repoRoot);
        writeJavaFile(repoRoot, "src/main/java/com/example/Utils.java", """
                package com.example;

                public class Utils {
                    public static int add(int a, int b) {
                        return a + b;
                    }
                }
                """);

        AnalysisResult result = analyzer.analyze(repoRoot.toString());

        assertThat(result.projectType().projectType()).isEqualTo(ProjectType.UNKNOWN);
        assertThat(result.projectType().status()).isEqualTo(DetectionStatus.UNKNOWN);
    }

    // ---- Regression test: the exact bug found and fixed this session ----

    /**
     * This is the precise false-positive this session found in production: a comment/Javadoc
     * that merely mentions "@RestController" by name — e.g. documenting that a class does NOT
     * use it — was previously read as real annotation usage by a naive content.contains(...)
     * check, misclassifying an otherwise-unremarkable utility class as REST_APPLICATION.
     * If this test ever goes red, the comment-stripping fix in AnalyzerService has regressed.
     */
    @Test
    void doesNotClassifyAsRest_whenAnnotationNameOnlyAppearsInAComment() throws IOException {
        Path repoRoot = Files.createTempDirectory("analyzer-test-comment-false-positive");
        writePom(repoRoot);
        writeJavaFile(repoRoot, "src/main/java/com/example/Utils.java", """
                package com.example;

                /**
                 * Deliberately has:
                 *   - no @RestController / @Controller / Spring annotations
                 *   - no public static void main entrypoint
                 */
                public class Utils {
                    public static int add(int a, int b) {
                        return a + b;
                    }
                }
                """);

        AnalysisResult result = analyzer.analyze(repoRoot.toString());

        assertThat(result.projectType().projectType())
                .as("a comment merely naming @RestController must not trigger REST classification")
                .isEqualTo(ProjectType.UNKNOWN);
    }

    // ---- Regression companion: real annotation usage must still work alongside a comment ----

    @Test
    void stillClassifiesAsRest_whenRealAnnotationCoexistsWithAnUnrelatedComment() throws IOException {
        Path repoRoot = Files.createTempDirectory("analyzer-test-real-annotation-with-comment");
        writePom(repoRoot);
        writeJavaFile(repoRoot, "src/main/java/com/example/RealController.java", """
                package com.example;

                import org.springframework.web.bind.annotation.RestController;

                /* unrelated header comment, nothing to do with annotations */
                @RestController
                public class RealController {
                }
                """);

        AnalysisResult result = analyzer.analyze(repoRoot.toString());

        assertThat(result.projectType().projectType())
                .as("a real annotation must still be detected even when an unrelated comment is present")
                .isEqualTo(ProjectType.REST_APPLICATION);
    }

    // ---- No build tool, no Java files at all ----

    @Test
    void classifiesUnknown_whenRepoHasNoRecognizedBuildToolAndNoJavaFiles() throws IOException {
        Path repoRoot = Files.createTempDirectory("analyzer-test-empty");
        Files.writeString(repoRoot.resolve("README.md"), "Just a readme, no code here.");

        AnalysisResult result = analyzer.analyze(repoRoot.toString());

        assertThat(result.projectType().projectType()).isEqualTo(ProjectType.UNKNOWN);
        assertThat(result.buildTool().status()).isEqualTo(DetectionStatus.UNKNOWN);
    }

    // ---- Plain Java (no build tool), console-shaped ----

    @Test
    void classifiesConsoleApplication_withNoBuildToolAtAll() throws IOException {
        Path repoRoot = Files.createTempDirectory("analyzer-test-plain-java-console");
        writeJavaFile(repoRoot, "App.java", """
                import java.util.Scanner;

                public class App {
                    public static void main(String[] args) {
                        Scanner sc = new Scanner(System.in);
                        System.out.println(sc.nextLine());
                    }
                }
                """);

        AnalysisResult result = analyzer.analyze(repoRoot.toString());

        assertThat(result.buildTool().value()).isEqualTo("Plain Java");
        assertThat(result.projectType().projectType()).isEqualTo(ProjectType.CONSOLE_APPLICATION);
        // Exactly one candidate main class with no ambiguity — should be resolved, not UNKNOWN.
        assertThat(result.mainClass().status()).isEqualTo(DetectionStatus.DETECTED);
    }

    // ---- Ambiguous main class must not be silently guessed ----

    @Test
    void mainClassIsUnknown_whenMultipleMainMethodCandidatesExistWithNoExplicitConfig() throws IOException {
        Path repoRoot = Files.createTempDirectory("analyzer-test-ambiguous-main");
        writePom(repoRoot);
        writeJavaFile(repoRoot, "src/main/java/com/example/AppOne.java", """
                package com.example;
                public class AppOne {
                    public static void main(String[] args) { }
                }
                """);
        writeJavaFile(repoRoot, "src/main/java/com/example/AppTwo.java", """
                package com.example;
                public class AppTwo {
                    public static void main(String[] args) { }
                }
                """);

        AnalysisResult result = analyzer.analyze(repoRoot.toString());

        assertThat(result.mainClass().status())
                .as("two main() candidates with nothing explicit to disambiguate them must not be silently guessed")
                .isEqualTo(DetectionStatus.UNKNOWN);
    }
}
