package com.example.platform.controller;

import com.example.platform.entity.Project;
import com.example.platform.repository.ProjectRepository;
import com.example.platform.service.RunService;
import com.example.platform.service.DatabaseProvisionerService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.platform.entity.User;
import com.example.platform.service.CurrentUserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.DeleteMapping;
import com.example.platform.exception.ProjectNotFoundException;
import com.example.platform.service.RepositoryService;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;
import java.util.UUID;
import com.example.platform.exception.ProjectNotFoundException;

@RestController
@RequestMapping("/api/projects")
public class RunController {

    // Chooses how /run and /restart decide "is this container ready," per project type.
    // See RunService.HealthCheckStrategy for why UNSUPPORTED/UNKNOWN get NONE rather than
    // reusing the console app's CONTAINER_STATE check (or falling through to HTTP) — neither
    // check can honestly answer "ready" for a project with no REST interface and no console
    // loop, and a project.getProjectTypeEnum() of null (never analyzed) is treated the same
    // way as UNSUPPORTED/UNKNOWN here, since we equally can't assume it's REST or console.
    private static com.example.platform.service.RunService.HealthCheckStrategy healthCheckStrategyFor(Project project) {
        com.example.platform.analyzer.ProjectType type = project.getProjectTypeEnum();
        if (type == com.example.platform.analyzer.ProjectType.REST_APPLICATION) {
            return com.example.platform.service.RunService.HealthCheckStrategy.HTTP;
        }
        if (type == com.example.platform.analyzer.ProjectType.CONSOLE_APPLICATION) {
            return com.example.platform.service.RunService.HealthCheckStrategy.CONTAINER_STATE;
        }
        return com.example.platform.service.RunService.HealthCheckStrategy.NONE;
    }

    private final ProjectRepository projectRepository;
    private final RunService runService;
    private final DatabaseProvisionerService databaseProvisionerService;
    private final CurrentUserService currentUserService;
    private final RepositoryService repositoryService;

    public RunController(ProjectRepository projectRepository, RunService runService,
                         DatabaseProvisionerService databaseProvisionerService,
                         CurrentUserService currentUserService, RepositoryService repositoryService) {
        this.projectRepository = projectRepository;
        this.runService = runService;
        this.databaseProvisionerService = databaseProvisionerService;
        this.currentUserService = currentUserService;
        this.repositoryService = repositoryService;
    }

    @PostMapping("/{id}/run")
    public Map<String, Object> run(@PathVariable UUID id, HttpSession session) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + id));

        User currentUser = currentUserService.requireCurrentUser(session);
        currentUserService.requireOwnership(currentUser, project.getOwner() != null ? project.getOwner().getId() : null);

        if (project.getDockerImageId() == null) {
            throw new IllegalStateException("Project has not been built yet. Call /build first.");
        }

        DatabaseProvisionerService.DbCredentials dbCredentials = null;

        // Provision a database only if this project actually needs one (per the analyzer's
        // detected database driver) — previously this ran unconditionally for every project,
        // leaving an unused Postgres database behind for projects that never touch a DB.
        // A project analyzed before this change (detectedDatabaseDriver == null, i.e. it was
        // never recorded) is treated the same as "driver unknown" — no provisioning, not a
        // silent guess in either direction.
        String detectedDriver = project.getDetectedDatabaseDriver();
        boolean needsDatabase = detectedDriver != null;
        boolean isMySql = "MySQL".equals(detectedDriver);
        // Only ever true right after a fresh CREATE DATABASE with no schema.sql found — never on
        // a later /run against an already-provisioned database, so the warning doesn't repeat
        // forever once the person has already seen it once for this project.
        boolean freshlyProvisionedWithoutSchema = false;

        if (needsDatabase) {
            if (project.getDbName() == null) {
                dbCredentials = isMySql
                        ? databaseProvisionerService.provisionMySqlDatabase(id)
                        : databaseProvisionerService.provisionPostgresDatabase(id);
                project.setDbCredentials(dbCredentials.dbName(), dbCredentials.username(), dbCredentials.password());
                projectRepository.save(project);

                // Only on first-ever provisioning for this project — running this again on a
                // later /run against the same (already-provisioned) database would try to
                // CREATE TABLE against tables that already exist and fail. See
                // DatabaseProvisionerService.runSchemaScriptIfPresent for the schema.sql
                // convention and why this is a no-op when the repo doesn't have one.
                boolean schemaExecuted = databaseProvisionerService.runSchemaScriptIfPresent(project.getClonePath(), dbCredentials);
                freshlyProvisionedWithoutSchema = !schemaExecuted;
            } else {
                // Re-derive type/port from the persisted detectedDatabaseDriver rather than
                // storing them separately — detectedDatabaseDriver only changes on re-analyze,
                // so it's already the stable source of truth for "which kind of DB is this."
                dbCredentials = new DatabaseProvisionerService.DbCredentials(
                        project.getDbName(), project.getDbUsername(), project.getDbPassword(),
                        detectedDriver, isMySql ? 3306 : 5432);
            }
        }

        RunService.RunResult result = runService.runContainer(
                id, project.getDockerImageId(), dbCredentials,
                healthCheckStrategyFor(project)
        );

        project.setContainerId(result.containerId());
        project.setHostPort(result.hostPort());
        project.setStatus(result.healthy() ? "RUNNING" : "RUN_UNHEALTHY");
        projectRepository.save(project);

        // LinkedHashMap, not Map.of(...) — Map.of disallows null values and we only want
        // "schemaWarning" present at all when it's actually relevant, not present-with-null.
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("containerId", result.containerId());
        response.put("hostPort", result.hostPort());
        response.put("healthy", result.healthy());
        response.put("url", "http://localhost:" + result.hostPort());

        if (freshlyProvisionedWithoutSchema) {
            response.put("schemaWarning",
                    "A " + detectedDriver + " database was provisioned for this project, but no schema.sql "
                            + "was found in the repository, so no tables were created. If the application "
                            + "expects tables to already exist, add a schema.sql file to the repo root (or "
                            + "db/, sql/, database/) and re-run, or create the tables manually.");
        }

        return response;
    }

    @PostMapping("/{id}/stop")
    public Map<String, Object> stop(@PathVariable UUID id, HttpSession session) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + id));

        User currentUser = currentUserService.requireCurrentUser(session);
        currentUserService.requireOwnership(currentUser, project.getOwner() != null ? project.getOwner().getId() : null);

        runService.stopContainer(project.getContainerId());
        project.setStatus("STOPPED");
        projectRepository.save(project);

        return Map.of("status", "STOPPED");
    }

    @PostMapping("/{id}/restart")
    public Map<String, Object> restart(@PathVariable UUID id, HttpSession session) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + id));

        User currentUser = currentUserService.requireCurrentUser(session);
        currentUserService.requireOwnership(currentUser, project.getOwner() != null ? project.getOwner().getId() : null);

        if (project.getContainerId() == null) {
            throw new IllegalStateException("Project has never been run. Call /run first.");
        }
        if (project.getHostPort() == null) {
            throw new IllegalStateException("Project has no recorded port — call /run instead of /restart.");
        }

        RunService.RunResult result = runService.restartContainer(
                id, project.getContainerId(), project.getHostPort(),
                healthCheckStrategyFor(project)
        );

        project.setStatus(result.healthy() ? "RUNNING" : "RUN_UNHEALTHY");
        projectRepository.save(project);

        return Map.of(
                "healthy", result.healthy(),
                "url", "http://localhost:" + result.hostPort()
        );
    }

    public record DeleteConfirmation(String confirmProjectName) {}

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable UUID id,
                                      @RequestBody DeleteConfirmation confirmation,
                                      HttpSession session) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + id));

        User currentUser = currentUserService.requireCurrentUser(session);
        currentUserService.requireOwnership(currentUser, project.getOwner() != null ? project.getOwner().getId() : null);

        if (!project.getName().equals(confirmation.confirmProjectName())) {
            throw new IllegalArgumentException("Confirmation name does not match project name. Nothing was deleted.");
        }

        runService.removeContainer(project.getContainerId());
        databaseProvisionerService.deprovisionDatabase(
                project.getDbName(), project.getDbUsername(), project.getDetectedDatabaseDriver());
        repositoryService.deleteWorkspace(id);

        projectRepository.delete(project);

        return Map.of("deleted", true);
    }
}