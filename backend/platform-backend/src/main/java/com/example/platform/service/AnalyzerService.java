package com.example.platform.service;

import com.example.platform.analyzer.AnalysisResult;
import com.example.platform.analyzer.Detected;
import com.example.platform.analyzer.ProjectType;
import com.example.platform.analyzer.ProjectTypeDetection;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AnalyzerService {

    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    // Matches the Gradle "application" plugin's mainClassName/mainClass.set(...) style entries
    // and the Spring Boot Gradle plugin's springBoot { mainClass = '...' } block. Best-effort
    // text matching, not a real Groovy/Kotlin parser — acceptable for the "minimal Gradle
    // support" scope: it only needs to catch an *explicit* entrypoint when one is configured.
    private static final Pattern GRADLE_MAIN_CLASS_PATTERN =
            Pattern.compile("mainClass(?:Name)?\\s*(?:=|\\.set\\()\\s*['\"]([\\w.]+)['\"]");

    private static final Pattern GRADLE_JAVA_VERSION_PATTERN =
            Pattern.compile("(?:sourceCompatibility|targetCompatibility)\\s*=?\\s*['\"]?(?:JavaVersion\\.VERSION_)?(\\d+(?:\\.\\d+)?)['\"]?");

    // Strips // line comments and /* ... */ block comments (including Javadoc) before source
    // signals are scanned. Without this, a comment merely mentioning an annotation name — e.g.
    // Javadoc explaining "this class has no @RestController" — reads as real annotation usage,
    // since the original scan was a plain content.contains(...) check with no awareness of
    // comments at all. Best-effort regex, not a real Java lexer: a string literal containing
    // "//" or "/*" could theoretically be stripped too, but that's a much rarer, lower-stakes
    // false negative than the false positive this fixes.
    private static final Pattern COMMENT_PATTERN =
            Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    private String stripComments(String content) {
        return COMMENT_PATTERN.matcher(content).replaceAll("");
    }

    public AnalysisResult analyze(String clonePath) {
        Path repoRoot = Path.of(clonePath);
        File pomFile = repoRoot.resolve("pom.xml").toFile();
        File gradleGroovyFile = repoRoot.resolve("build.gradle").toFile();
        File gradleKotlinFile = repoRoot.resolve("build.gradle.kts").toFile();

        if (pomFile.exists()) {
            return analyzeMaven(repoRoot, pomFile);
        }
        if (gradleGroovyFile.exists() || gradleKotlinFile.exists()) {
            File gradleFile = gradleGroovyFile.exists() ? gradleGroovyFile : gradleKotlinFile;
            return analyzeGradle(repoRoot, gradleFile);
        }

        // No recognized build descriptor. Rather than giving up immediately (as before), check
        // whether this is simply a plain, build-tool-free Java project — a single .java file (or
        // a small handful) with a main() method is a completely valid console app per the PRD's
        // own example (§13), and shouldn't be classified UNKNOWN just because there's no pom.xml.
        JavaSourceSignals sourceSignals = scanJavaSources(repoRoot);
        List<String> mainClassCandidates = findMainClassCandidates(repoRoot);

        if (!sourceSignals.hasAnyJavaFiles()) {
            return new AnalysisResult(
                    Detected.unknown(), Detected.unknown(), Detected.unknown(),
                    Detected.unknown(), detectDocker(repoRoot), Detected.unknown(),
                    ProjectTypeDetection.unknown("No pom.xml, build.gradle, or .java files found — cannot classify this repository."),
                    Detected.unknown()
            );
        }

        return new AnalysisResult(
                Detected.detected("Plain Java"),
                Detected.unknown(),
                Detected.unknown(),
                Detected.unknown(),
                detectDocker(repoRoot),
                detectDatabaseDriverFromSource(repoRoot),
                detectProjectType(List.of(), Detected.unknown(), sourceSignals),
                detectMainClass(null, mainClassCandidates)
        );
    }

    /**
     * The plain-Java path has no dependency declarations to inspect (that's the whole point —
     * no build tool), so this looks for the JDBC URL scheme string literal directly in source
     * (e.g. "jdbc:mysql://...", "jdbc:postgresql://..."). This is enough of a signal to (a) decide
     * whether to provision a database at all, and (b) which one — see RunController and
     * DockerfileGenerator's plain-Java driver-jar step, both of which need to know which JDBC
     * driver to make available since plain javac can't resolve it from a dependency.
     */
    private Detected<String> detectDatabaseDriverFromSource(Path repoRoot) {
        boolean[] hasMysql = {false};
        boolean[] hasPostgres = {false};

        try (var paths = Files.walk(repoRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains(File.separator + ".git" + File.separator))
                    .forEach(p -> {
                        String content;
                        try {
                            content = Files.readString(p);
                        } catch (IOException e) {
                            return;
                        }
                        if (content.contains("jdbc:mysql")) hasMysql[0] = true;
                        if (content.contains("jdbc:postgresql")) hasPostgres[0] = true;
                    });
        } catch (IOException e) {
            // Best-effort — no signal found if the tree can't be walked.
        }

        if (hasMysql[0]) return Detected.detected("MySQL");
        if (hasPostgres[0]) return Detected.detected("PostgreSQL");
        return Detected.unknown();
    }

    private AnalysisResult analyzeMaven(Path repoRoot, File pomFile) {
        Document pom = parsePom(pomFile);
        List<String> dependencyArtifactIds = extractDependencyArtifactIds(pom);
        Detected<String> framework = detectFramework(pom, dependencyArtifactIds);
        JavaSourceSignals sourceSignals = scanJavaSources(repoRoot);
        List<String> mainClassCandidates = findMainClassCandidates(repoRoot);
        String explicitMainClass = firstElementTextByTagName(pom, "mainClass");

        return new AnalysisResult(
                Detected.detected("Maven"),
                framework,
                detectJavaVersion(pom),
                detectOpenApi(dependencyArtifactIds),
                detectDocker(repoRoot),
                detectDatabaseDriver(dependencyArtifactIds),
                detectProjectType(dependencyArtifactIds, framework, sourceSignals),
                detectMainClass(explicitMainClass, mainClassCandidates)
        );
    }

    /**
     * Deliberately minimal: Gradle build files are Groovy or Kotlin code, not a structured
     * format like pom.xml's XML, so we don't attempt a real parse. This is enough to (a) stop
     * misclassifying Gradle projects as "no pom.xml -> UNKNOWN" and (b) generate a working
     * Dockerfile for them. Dependency-driven signals (Spring Boot framework, web dependency,
     * OpenAPI, DB driver) are best-effort text matches and may miss unconventional build files;
     * the REST-vs-console classification itself does NOT depend on this, since it's driven by
     * scanning .java sources directly (see detectProjectType).
     */
    private AnalysisResult analyzeGradle(Path repoRoot, File gradleFile) {
        String gradleText;
        try {
            gradleText = Files.readString(gradleFile.toPath());
        } catch (IOException e) {
            gradleText = "";
        }

        JavaSourceSignals sourceSignals = scanJavaSources(repoRoot);
        List<String> mainClassCandidates = findMainClassCandidates(repoRoot);

        boolean hasSpringBootPlugin = gradleText.contains("org.springframework.boot");
        Detected<String> framework = hasSpringBootPlugin ? Detected.detected("Spring Boot") : Detected.unknown();

        boolean hasWebDependency = gradleText.contains("spring-boot-starter-web");
        // Reuse the same dependency-list-shaped input the Maven path uses, so
        // detectProjectType's "isSpringBoot && hasWebDependency" branch works identically.
        List<String> pseudoDependencyIds = new ArrayList<>();
        if (hasWebDependency) pseudoDependencyIds.add("spring-boot-starter-web");

        boolean hasOpenApi = gradleText.contains("springdoc") || gradleText.contains("swagger");
        Detected<Boolean> openApiAvailable = hasOpenApi ? Detected.detected(true) : Detected.unknown();

        Detected<String> databaseDriver;
        if (gradleText.contains("postgresql")) {
            databaseDriver = Detected.detected("PostgreSQL");
        } else if (gradleText.contains("mysql")) {
            databaseDriver = Detected.detected("MySQL");
        } else {
            databaseDriver = Detected.unknown();
        }

        Detected<String> javaVersion = Detected.unknown();
        Matcher versionMatcher = GRADLE_JAVA_VERSION_PATTERN.matcher(gradleText);
        if (versionMatcher.find()) {
            javaVersion = Detected.inferred(versionMatcher.group(1));
        }

        String explicitMainClass = null;
        Matcher mainClassMatcher = GRADLE_MAIN_CLASS_PATTERN.matcher(gradleText);
        if (mainClassMatcher.find()) {
            explicitMainClass = mainClassMatcher.group(1);
        }

        return new AnalysisResult(
                Detected.detected("Gradle"),
                framework,
                javaVersion,
                openApiAvailable,
                detectDocker(repoRoot),
                databaseDriver,
                detectProjectType(pseudoDependencyIds, framework, sourceSignals),
                detectMainClass(explicitMainClass, mainClassCandidates)
        );
    }

    /**
     * Signals pulled directly out of the repository's .java source files — the analyzer
     * philosophy (PRD §6/§23) is to inspect the repo, not just infer from pom.xml
     * dependencies, so a Spring Boot project without a controller isn't blindly assumed
     * to be a REST application. Also runs identically regardless of build tool (or lack
     * of one), which is what lets plain-Java and Gradle projects get classified at all.
     */
    private record JavaSourceSignals(
            boolean hasRestControllerAnnotation,
            boolean hasControllerAnnotation,
            boolean hasMainMethod,
            boolean hasInteractiveStdIo,
            boolean hasAnyJavaFiles
    ) {}

    private JavaSourceSignals scanJavaSources(Path repoRoot) {
        boolean[] restController = {false};
        boolean[] controller = {false};
        boolean[] mainMethod = {false};
        boolean[] interactiveIo = {false};
        boolean[] anyJavaFiles = {false};

        try (var paths = Files.walk(repoRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains(File.separator + "target" + File.separator))
                    .filter(p -> !p.toString().contains(File.separator + "build" + File.separator))
                    .filter(p -> !p.toString().contains(File.separator + ".git" + File.separator))
                    .forEach(p -> {
                        anyJavaFiles[0] = true;
                        String rawContent;
                        try {
                            rawContent = Files.readString(p);
                        } catch (IOException e) {
                            return; // unreadable file — skip, don't fail the whole analysis over it
                        }
                        // Scan comment-stripped content only — see stripComments() for why.
                        String content = stripComments(rawContent);
                        if (content.contains("@RestController")) restController[0] = true;
                        if (content.contains("@Controller")) controller[0] = true;
                        if (content.contains("public static void main")) mainMethod[0] = true;
                        if (content.contains("new Scanner(System.in)") || content.contains("System.in")
                                || content.contains("BufferedReader")) {
                            interactiveIo[0] = true;
                        }
                    });
        } catch (IOException e) {
            // Best-effort — if we can't walk the tree, we simply found no signals.
        }

        return new JavaSourceSignals(restController[0], controller[0], mainMethod[0], interactiveIo[0], anyJavaFiles[0]);
    }

    /**
     * Finds every class in the repo that declares {@code public static void main}, returning
     * each as a fully-qualified class name (package + filename, per Java's requirement that a
     * public top-level class's name match its file name). Used to pick a Docker ENTRYPOINT for
     * console apps that don't produce a jar with a manifest-declared Main-Class.
     */
    private List<String> findMainClassCandidates(Path repoRoot) {
        List<String> candidates = new ArrayList<>();

        try (var paths = Files.walk(repoRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains(File.separator + "target" + File.separator))
                    .filter(p -> !p.toString().contains(File.separator + "build" + File.separator))
                    .filter(p -> !p.toString().contains(File.separator + ".git" + File.separator))
                    .forEach(p -> {
                        String rawContent;
                        try {
                            rawContent = Files.readString(p);
                        } catch (IOException e) {
                            return;
                        }
                        // Scan comment-stripped content — see stripComments() for why (a comment
                        // merely mentioning "public static void main" shouldn't count as a real
                        // entrypoint any more than a comment mentioning an annotation name should
                        // count as real annotation usage).
                        String content = stripComments(rawContent);
                        if (!content.contains("public static void main")) return;

                        String fileName = p.getFileName().toString();
                        String className = fileName.substring(0, fileName.length() - ".java".length());

                        // PACKAGE_PATTERN still runs against rawContent: stripping comments doesn't
                        // change where the package declaration is, and there's no reason to redo the
                        // regex against a second string when the original already works correctly here.
                        Matcher packageMatcher = PACKAGE_PATTERN.matcher(rawContent);
                        String fqcn = packageMatcher.find()
                                ? packageMatcher.group(1) + "." + className
                                : className;

                        candidates.add(fqcn);
                    });
        } catch (IOException e) {
            // Best-effort — no candidates found if the tree can't be walked.
        }

        return candidates;
    }

    /**
     * Resolves which class to actually run. Prefers an explicit entrypoint the build file
     * already configured (Maven's <mainClass>, Gradle's mainClass/mainClassName) since that's
     * an authoritative signal, not a guess. Falls back to source scanning only when exactly one
     * candidate exists — with two or more candidates and nothing explicit to disambiguate them,
     * this deliberately returns UNKNOWN rather than silently picking one, matching the rest of
     * this class's "never guess blindly" approach. Callers (BuildService) must treat an UNKNOWN
     * main class on a console app as a hard stop, not a silent fallback to "java -jar app.jar".
     */
    private Detected<String> detectMainClass(String explicitMainClass, List<String> candidates) {
        if (explicitMainClass != null && !explicitMainClass.isBlank()) {
            return Detected.detected(explicitMainClass);
        }
        if (candidates.size() == 1) {
            return Detected.detected(candidates.get(0));
        }
        return Detected.unknown();
    }

    private ProjectTypeDetection detectProjectType(List<String> dependencyArtifactIds,
                                                     Detected<String> framework,
                                                     JavaSourceSignals signals) {
        boolean hasRestSignal = signals.hasRestControllerAnnotation() || signals.hasControllerAnnotation();
        boolean hasWebDependency = dependencyArtifactIds.stream()
                .anyMatch(id -> id.contains("starter-web") || id.contains("spring-web"));
        boolean isSpringBoot = "Spring Boot".equals(framework.value());
        boolean hasConsoleSignal = signals.hasMainMethod() && signals.hasInteractiveStdIo();

        if (hasRestSignal) {
            return ProjectTypeDetection.detected(ProjectType.REST_APPLICATION,
                    "Found @RestController and/or @Controller annotation(s) in the project's source code.");
        }
        if (isSpringBoot && hasWebDependency) {
            return ProjectTypeDetection.inferred(ProjectType.REST_APPLICATION,
                    "Spring Boot project with a web dependency, but no @RestController/@Controller was found " +
                            "directly in source — inferred from framework and dependencies alone.");
        }
        if (hasConsoleSignal) {
            return ProjectTypeDetection.detected(ProjectType.CONSOLE_APPLICATION,
                    "Java main() method found using Scanner/System.in/BufferedReader for interactive input, " +
                            "and no REST controller was detected.");
        }
        if (signals.hasMainMethod()) {
            return ProjectTypeDetection.inferred(ProjectType.UNSUPPORTED,
                    "A main() method was found, but no interactive stdin usage or REST controller was detected — " +
                            "this project doesn't match a currently supported interface pattern.");
        }
        return ProjectTypeDetection.unknown(
                "No REST controllers, web dependency, or console entry point could be identified in the source.");
    }

    private Document parsePom(File pomFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Disable external entity resolution to prevent XXE attacks when parsing
            // untrusted pom.xml files from cloned repositories.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(pomFile);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse pom.xml: " + e.getMessage(), e);
        }
    }

    private List<String> extractDependencyArtifactIds(Document pom) {
        List<String> result = new ArrayList<>();
        NodeList dependencies = pom.getElementsByTagName("dependency");
        for (int i = 0; i < dependencies.getLength(); i++) {
            Element dep = (Element) dependencies.item(i);
            String artifactId = childText(dep, "artifactId");
            if (artifactId != null) {
                result.add(artifactId);
            }
        }
        return result;
    }

    private Detected<String> detectFramework(Document pom, List<String> dependencyArtifactIds) {
        NodeList parents = pom.getElementsByTagName("parent");
        if (parents.getLength() > 0) {
            Element parent = (Element) parents.item(0);
            String parentArtifact = childText(parent, "artifactId");
            if ("spring-boot-starter-parent".equals(parentArtifact)) {
                return Detected.detected("Spring Boot");
            }
        }
        boolean hasSpringBootDependency = dependencyArtifactIds.stream()
                .anyMatch(id -> id.startsWith("spring-boot-starter"));
        if (hasSpringBootDependency) {
            return Detected.detected("Spring Boot");
        }
        return Detected.unknown();
    }

    private Detected<String> detectJavaVersion(Document pom) {
        Optional<String> javaVersion = firstPropertyValue(pom, "java.version");
        if (javaVersion.isPresent()) {
            return Detected.detected(javaVersion.get());
        }
        Optional<String> release = firstPropertyValue(pom, "maven.compiler.release");
        if (release.isPresent()) {
            return Detected.detected(release.get());
        }
        Optional<String> source = firstPropertyValue(pom, "maven.compiler.source");
        if (source.isPresent()) {
            // Source version without an explicit release/java.version is a weaker signal —
            // it constrains compilation but doesn't guarantee the runtime target. Inferred, not detected.
            return Detected.inferred(source.get());
        }
        return Detected.unknown();
    }

    private Detected<Boolean> detectOpenApi(List<String> dependencyArtifactIds) {
        boolean hasOpenApi = dependencyArtifactIds.stream()
                .anyMatch(id -> id.contains("springdoc") || id.contains("swagger"));
        return hasOpenApi ? Detected.detected(true) : Detected.unknown();
    }

    private Detected<Boolean> detectDocker(Path repoRoot) {
        boolean hasDockerfile = repoRoot.resolve("Dockerfile").toFile().exists();
        return hasDockerfile ? Detected.detected(true) : Detected.unknown();
    }

    private Detected<String> detectDatabaseDriver(List<String> dependencyArtifactIds) {
        if (dependencyArtifactIds.contains("postgresql")) {
            return Detected.detected("PostgreSQL");
        }
        if (dependencyArtifactIds.stream().anyMatch(id -> id.contains("mysql"))) {
            return Detected.detected("MySQL");
        }
        return Detected.unknown();
    }

    private Optional<String> firstPropertyValue(Document pom, String propertyName) {
        NodeList propertiesNodes = pom.getElementsByTagName("properties");
        if (propertiesNodes.getLength() == 0) return Optional.empty();

        Element properties = (Element) propertiesNodes.item(0);
        NodeList children = properties.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeName().equals(propertyName)) {
                return Optional.of(children.item(i).getTextContent().trim());
            }
        }
        return Optional.empty();
    }

    /** Finds the first element anywhere in the document with the given tag name, e.g. the
     * <mainClass> configured inside a maven-jar-plugin, shade-plugin, assembly-plugin, or
     * spring-boot-maven-plugin <configuration> block — wherever it happens to live. */
    private String firstElementTextByTagName(Document pom, String tagName) {
        NodeList matches = pom.getElementsByTagName(tagName);
        if (matches.getLength() == 0) return null;
        return matches.item(0).getTextContent().trim();
    }

    private String childText(Element parent, String tagName) {
        NodeList children = parent.getElementsByTagName(tagName);
        if (children.getLength() == 0) return null;
        return children.item(0).getTextContent().trim();
    }
}
