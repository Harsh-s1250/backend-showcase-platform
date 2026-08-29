package com.example.platform.service;

import com.example.platform.analyzer.InterfaceType;
import com.example.platform.analyzer.ProjectType;
import com.example.platform.analyzer.ProjectTypeDetection;
import com.example.platform.entity.Project;
import com.example.platform.service.ExperienceService.ExperienceResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ExperienceService is pure decision logic — given a Project's classification and status, what
 * should the showcase page render? These tests build real Project instances (not mocks) since
 * the class's public constructor + setters are enough to exercise every state without needing
 * a database. Deliberately covers every ProjectType x isRunning combination the PRD cares about,
 * not just the happy path — this is exactly the class where the "unsupported project stuck on a
 * generic failure message" bug from this session would have been caught immediately if it had
 * existed at test time.
 */
class ExperienceServiceTest {

    private final ExperienceService service = new ExperienceService();

    private Project projectWithType(ProjectType type, String status) {
        Project project = new Project("test-project", "https://github.com/example/test", "main");
        project.setProjectTypeDetection(ProjectTypeDetection.detected(type, "test reason"));
        project.setStatus(status);
        return project;
    }

    @Test
    void restApplication_running_getsApiExplorerAndIsAvailable() {
        ExperienceResult result = service.resolve(projectWithType(ProjectType.REST_APPLICATION, "RUNNING"));

        assertThat(result.interfaceType()).isEqualTo(InterfaceType.API_EXPLORER);
        assertThat(result.interfaceAvailable()).isTrue();
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.isRunning()).isTrue();
    }

    @Test
    void restApplication_notRunning_reportsNotDeployed() {
        ExperienceResult result = service.resolve(projectWithType(ProjectType.REST_APPLICATION, "BUILT"));

        assertThat(result.status()).isEqualTo("NOT_DEPLOYED");
        assertThat(result.isRunning()).isFalse();
    }

    @Test
    void consoleApplication_running_getsBrowserTerminalAndIsAvailable() {
        ExperienceResult result = service.resolve(projectWithType(ProjectType.CONSOLE_APPLICATION, "RUNNING"));

        assertThat(result.interfaceType()).isEqualTo(InterfaceType.BROWSER_TERMINAL);
        assertThat(result.interfaceAvailable()).isTrue();
        assertThat(result.status()).isEqualTo("READY");
    }

    /**
     * The exact combination the "unsupported project" bug hinged on this session: an
     * UNSUPPORTED/UNKNOWN project reaching RUNNING must report interfaceType NONE with
     * interfaceAvailable=false and status DEPLOYED_NO_INTERFACE — the honest "we don't have
     * an interface for this, but it did deploy" state, not a generic failure.
     */
    @Test
    void unsupportedApplication_running_reportsNoInterfaceAvailableButDeployed() {
        ExperienceResult result = service.resolve(projectWithType(ProjectType.UNSUPPORTED, "RUNNING"));

        assertThat(result.interfaceType()).isEqualTo(InterfaceType.NONE);
        assertThat(result.interfaceAvailable()).isFalse();
        assertThat(result.status()).isEqualTo("DEPLOYED_NO_INTERFACE");
        assertThat(result.isRunning()).isTrue();
    }

    @Test
    void unknownApplication_running_reportsNoInterfaceAvailableButDeployed() {
        ExperienceResult result = service.resolve(projectWithType(ProjectType.UNKNOWN, "RUNNING"));

        assertThat(result.interfaceType()).isEqualTo(InterfaceType.NONE);
        assertThat(result.interfaceAvailable()).isFalse();
        assertThat(result.status()).isEqualTo("DEPLOYED_NO_INTERFACE");
    }

    @Test
    void unsupportedApplication_notRunning_reportsNotDeployed_notDeployedNoInterface() {
        // Before RunController's health-check-strategy fix, an UNSUPPORTED project could
        // never even reach RUNNING, so this branch (not-running) was the only one ever
        // observed in practice — worth keeping as an explicit case so a future regression
        // in RunController can't silently make DEPLOYED_NO_INTERFACE unreachable again.
        ExperienceResult result = service.resolve(projectWithType(ProjectType.UNSUPPORTED, "RUN_UNHEALTHY"));

        assertThat(result.status()).isEqualTo("NOT_DEPLOYED");
        assertThat(result.interfaceAvailable()).isFalse();
    }

    @Test
    void projectNeverAnalyzed_reportsNotAnalyzed() {
        Project project = new Project("test-project", "https://github.com/example/test", "main");
        // Deliberately no setProjectTypeDetection(...) call — this is the state right after
        // a project is created but before /analyze has ever run.

        ExperienceResult result = service.resolve(project);

        assertThat(result.status()).isEqualTo("NOT_ANALYZED");
        assertThat(result.projectType()).isNull();
        assertThat(result.interfaceType()).isEqualTo(InterfaceType.NONE);
        assertThat(result.interfaceAvailable()).isFalse();
    }
}
