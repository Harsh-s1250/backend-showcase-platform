# Backend Showcase Platform — Project Status
_Last updated: August 17, 2026_

## Overview

The platform's core promise — **paste a GitHub repo URL, get a live, interactive, database-backed API explorer, secured behind real GitHub login** — is fully working end to end as of Milestone 11.

Proven golden path:
```
Login (GitHub OAuth) → create project → clone → analyze → build (Docker)
→ run (auto-provisioned isolated DB) → OpenAPI Explorer → stop/restart
```

---

## ✅ Completed Milestones (1–11)

| # | Milestone | Status |
|---|---|---|
| 1 | Secure Build/Run Proof (Docker, manual script) | ✅ Complete |
| 2 | Platform Backend Skeleton (`platform-backend`, health endpoint) | ✅ Complete |
| 3 | Database & Persistence (Postgres + Flyway, `Project` entity) | ✅ Complete |
| 4 | GitHub Repository Cloning (`/clone`, safe `ProcessBuilder` usage) | ✅ Complete |
| 5 | Repository Analyzer (`pom.xml` parsing, DETECTED/INFERRED/UNKNOWN) | ✅ Complete |
| 6 | Docker Build Engine (`docker-java`, SSE log streaming) | ✅ Complete |
| 7 | Container Run Engine (dynamic ports, resource limits, health checks) | ✅ Complete |
| 8 | OpenAPI Explorer (proxy endpoint + Swagger UI) | ✅ Complete |
| 9 | Per-Project Database Provisioning (isolated DB per project) | ✅ Complete |
| 10 | GitHub OAuth + Ownership Enforcement | ✅ Complete |
| 11 | Lifecycle Controls — Stop / Restart | ✅ Complete (`/stop`, `/restart` verified working) |

## 🔶 In Progress

| # | Milestone | Status |
|---|---|---|
| 11b | Lifecycle Controls — Delete | **Implemented, not yet tested.** `DELETE /{id}` endpoint exists with name-confirmation safety check, but has never been exercised against a real project. Known gap: does not yet clean up the cloned workspace directory or drop the provisioned database — only removes the Docker container and the project's database row. |
| 12 | Public Routing / Reverse Proxy | **Design only — no code written yet.** Goal, architecture, and security reasoning defined (path-based `/p/{projectId}/...` routing via a Spring controller, not Nginx/Traefik). `ProxyController.java` has not been created. |

---

## 🚧 Not Yet Started

| # | Feature | Notes |
|---|---|---|
| 13 | **Cleanup & expiry policies** | No automatic cleanup of old workspace clones, stale containers, or unused provisioned databases. Flagged repeatedly (Milestones 4, 7, 9) and now also true of `/delete`'s incomplete cleanup. |
| 14 | **MySQL support** | PRD mentions it; deferred in favor of Postgres-only for MVP. |
| 15 | **Full JWT/Bearer auth in the API Explorer** | Explorer currently only handles unauthenticated endpoints inside the showcased project itself. |
| 16 | **User-provided Dockerfile support** | Analyzer detects `dockerPresent`, but the Build Engine always overwrites with a generated Dockerfile. |
| 17 | **Console/CLI-based project support** | For plain Java console apps (stdin/stdout, no HTTP server). Needs a fundamentally different feature — a web-based terminal (`xterm.js` + WebSocket-driven `docker run -it`) rather than the OpenAPI Explorer pipeline. Not part of the current REST-API-focused architecture. |
| — | **Credential security hardening** | Provisioned DB passwords (Milestone 9) and GitHub access tokens (Milestone 10) are both stored in plaintext in the platform's own database. Production-grade would need encryption at rest or a secrets manager. |
| — | **Session persistence across restarts** | Sessions are in-memory (Tomcat default); every `mvn spring-boot:run` restart during development logs everyone out. Production would need Redis-backed sessions or JWTs. |
| — | **Health check accuracy** | Current health check only confirms a TCP port is open, not that the app finished booting. Caused real false-positive/false-negative confusion during Milestone 9 and 11 testing. A production version should poll an actual HTTP health endpoint (e.g., Actuator) instead. |
| — | **Resource-abuse hardening** | Clone-bombs, build resource ceilings, and rate limiting remain MVP-level, not production-hardened. |

---

## Architecture Snapshot

```
Single Spring Boot app (modular monolith): platform-backend
 ├── controller/   ProjectController, BuildController, RunController,
 │                 OpenApiController, AuthController
 ├── service/      RepositoryService, AnalyzerService, BuildService, RunService,
 │                 DatabaseProvisionerService, GitHubOAuthService, CurrentUserService
 ├── entity/       Project, User
 ├── analyzer/     DetectionStatus, Detected<T>, AnalysisResult
 ├── build/        DockerfileGenerator
 ├── dto/          CreateProjectRequest, ProjectResponse
 ├── exception/    RepositoryCloneException, ProjectNotFoundException, GlobalExceptionHandler
 └── config/       SecurityConfig

PostgreSQL — platform's own DB (showcase_platform) + one DB per showcased project
Docker Engine — build containers + run containers, resource-limited, localhost-only
Swagger UI (CDN) — served as a static explorer.html
GitHub OAuth — Authorization Code flow, CSRF-protected via state parameter
```

## Tech Debt / Things Worth Revisiting

- `owner_id` is `NULL` on any project created before Milestone 10 — these are now inaccessible via the API unless manually reassigned in the database (done once for the test project; a real migration/backfill strategy would be needed for a real user base).
- No `ProjectService` layer — controllers talk directly to `ProjectRepository`. Acceptable while logic stays simple; worth introducing if business logic grows further.
- Health check window was tuned once (30s → 120s) based on one specific app's real boot time; not adaptive or configurable per project.
- Spring Boot 4.1.0 continues to differ from older Spring Boot documentation/tutorials in ways that aren't always obvious up front (autoconfiguration module split, Flyway starter requirement, etc.) — worth double-checking behavior against the actual installed version rather than assuming.
