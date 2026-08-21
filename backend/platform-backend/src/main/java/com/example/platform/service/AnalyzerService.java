package com.example.platform.service;

import com.example.platform.analyzer.AnalysisResult;
import com.example.platform.analyzer.Detected;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
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
                    Detected.unknown(), detectDocker(repoRoot), Detected.unknown()
            );
        }

        Document pom = parsePom(pomFile);
        List<String> dependencyArtifactIds = extractDependencyArtifactIds(pom);

        return new AnalysisResult(
                Detected.detected("Maven"),
                detectFramework(pom, dependencyArtifactIds),
                detectJavaVersion(pom),
                detectOpenApi(dependencyArtifactIds),
                detectDocker(repoRoot),
                detectDatabaseDriver(dependencyArtifactIds)
        );
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