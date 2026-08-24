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

@Service
public class AnalyzerService {

    public AnalysisResult analyze(String clonePath) {
        Path repoRoot = Path.of(clonePath);
        File pomFile = repoRoot.resolve("pom.xml").toFile();

        if (!pomFile.exists()) {
            // No pom.xml at all — we genuinely don't know anything about this project yet.
            return new AnalysisResult(
                    Detected.unknown(), Detected.unknown(), Detected.unknown(),
                    Detected.unknown(), detectDocker(repoRoot), Detected.unknown(),
                    ProjectTypeDetection.unknown("No pom.xml found — cannot classify a project without build metadata.")
            );
        }

        Document pom = parsePom(pomFile);
        List<String> dependencyArtifactIds = extractDependencyArtifactIds(pom);
        Detected<String> framework = detectFramework(pom, dependencyArtifactIds);
        JavaSourceSignals sourceSignals = scanJavaSources(repoRoot);

        return new AnalysisResult(
                Detected.detected("Maven"),
                framework,
                detectJavaVersion(pom),
                detectOpenApi(dependencyArtifactIds),
                detectDocker(repoRoot),
                detectDatabaseDriver(dependencyArtifactIds),
                detectProjectType(dependencyArtifactIds, framework, sourceSignals)
        );
    }

    /**
     * Signals pulled directly out of the repository's .java source files — the analyzer
     * philosophy (PRD §6/§23) is to inspect the repo, not just infer from pom.xml
     * dependencies, so a Spring Boot project without a controller isn't blindly assumed
     * to be a REST application.
     */
    private record JavaSourceSignals(
            boolean hasRestControllerAnnotation,
            boolean hasControllerAnnotation,
            boolean hasMainMethod,
            boolean hasInteractiveStdIo
    ) {}

    private JavaSourceSignals scanJavaSources(Path repoRoot) {
        boolean[] restController = {false};
        boolean[] controller = {false};
        boolean[] mainMethod = {false};
        boolean[] interactiveIo = {false};

        try (var paths = Files.walk(repoRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains(File.separator + "target" + File.separator))
                    .filter(p -> !p.toString().contains(File.separator + ".git" + File.separator))
                    .forEach(p -> {
                        String content;
                        try {
                            content = Files.readString(p);
                        } catch (IOException e) {
                            return; // unreadable file — skip, don't fail the whole analysis over it
                        }
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

        return new JavaSourceSignals(restController[0], controller[0], mainMethod[0], interactiveIo[0]);
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

    private String childText(Element parent, String tagName) {
        NodeList children = parent.getElementsByTagName(tagName);
        if (children.getLength() == 0) return null;
        return children.item(0).getTextContent().trim();
    }
}