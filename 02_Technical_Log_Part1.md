# Backend Showcase Platform — Technical Implementation Log (Part 1 of 2)
_Milestones 1–6. Covers: what each milestone built, the reasoning behind design choices, exact files created/modified, and real bugs encountered + root causes._

---

## Milestone 1 — Secure Build/Run Proof (Docker)

**Goal:** Prove a Spring Boot/Maven project can be built and run safely inside Docker before writing any platform code.

**Files created:**
- `sample-task-api/pom.xml`, `TaskApiApplication.java` (minimal Spring Boot app, one `/api/health` endpoint)
- `platform-scripts/Dockerfile.generic` — multi-stage build (Maven build stage → slim JRE runtime stage)
- `platform-scripts/build-and-run.sh` — shell script: `docker build`, `docker run` with `--memory=256m --cpus=0.5`, polling health check via `curl`

**Key logic:**
- Multi-stage Dockerfile so the final runtime image never contains the Maven/JDK build toolchain — smaller image, smaller attack surface.
- `dependency:go-offline` before copying `src/` so Docker layer caching avoids re-downloading dependencies on every rebuild.
- Resource limits (`--memory`, `--cpus`) enforced structurally via Docker flags, not just documented as a requirement.

**Outcome:** Proved the riskiest, most uncertain part of the whole system (safe containerized build+run) before investing in GitHub OAuth, database schema, or a frontend.

---

## Milestone 2 — Platform Backend Skeleton

**Goal:** Stand up the platform's own Spring Boot app with one working endpoint, establishing package structure.

**Files created:**
- `backend/platform-backend/pom.xml` (Spring Web only)
- `PlatformApplication.java`, `controller/HealthController.java` (`GET /api/health` returning `Map<String,Object>`)

**Key decision:** Only created the `controller` package for this milestone — `service`/`repository`/`entity` deliberately left empty until real data existed to justify them (avoiding premature abstraction).

---

## Milestone 3 — Database & Persistence

**Goal:** Wire up PostgreSQL with Flyway migrations (not Hibernate auto-DDL), create the `Project` entity.

**Files created:**
- `V1__create_projects_table.sql` — `projects` table: UUID primary key (`gen_random_uuid()`, via `pgcrypto` extension), `name`, `github_repo_url`, `branch`, `status`, timestamps
- `entity/Project.java`, `repository/ProjectRepository.java`
- `dto/CreateProjectRequest.java`, `dto/ProjectResponse.java`
- `controller/ProjectController.java` — `POST /api/projects`, `GET /api/projects`

**Key logic:**
- `spring.jpa.hibernate.ddl-auto=validate` — Hibernate checks schema matches entities but never modifies it; Flyway is the only thing allowed to change schema.
- UUID primary keys chosen over auto-increment longs because project IDs would eventually be exposed in public URLs — sequential IDs leak information (count, creation order).
- DTOs (`ProjectResponse`) used instead of returning the JPA entity directly, to avoid coupling the API contract to the DB schema.

**Real bugs hit and root causes:**
1. **Flyway migrations silently never ran.** Root cause: Spring Boot 4.1's autoconfiguration was split into per-technology modules — `flyway-core` alone (even though it compiled and was present on the classpath) was no longer enough to trigger Spring's Flyway autoconfiguration. **Fix:** replaced `flyway-core` with `spring-boot-starter-flyway`, kept `flyway-database-postgresql` alongside it.
2. **`permission denied for schema public`** on first successful Flyway attempt. Root cause: Postgres 15+ no longer grants `CREATE` on the `public` schema to non-owner roles by default. **Fix:** `GRANT ALL ON SCHEMA public TO showcase_user;` plus default-privilege grants.

---

## Milestone 4 — GitHub Repository Cloning

**Goal:** Clone a public GitHub repo to an isolated workspace directory, associated with a `Project`.

**Files created:**
- `service/RepositoryService.java` — `cloneRepository(projectId, url, branch)`
- `exception/RepositoryCloneException.java`, `exception/GlobalExceptionHandler.java`
- `V2__add_clone_path_to_projects.sql` — adds `clone_path` column
- Modified `ProjectController.java` — `POST /{id}/clone`

**Key logic:**
- `ProcessBuilder` called with an **argument list** (`List.of("git", "clone", ...)`), never a concatenated shell string — this is what prevents command injection even if a malicious URL contained shell metacharacters.
- Clone directory named by the project's **UUID**, never by user-supplied text — prevents path traversal.
- `--depth 1 --single-branch` shallow clone — full history isn't needed to build/run, and it limits data transferred.
- `process.waitFor(120, SECONDS)` with forcible kill on timeout — partial defense against a clone that hangs forever.

**Real bugs hit and root causes:**
1. **Re-cloning an already-cloned project failed** (`git clone` refuses non-empty destination directories). **Fix:** added a `deleteRecursively()` helper to wipe the existing workspace directory before re-cloning, making `/clone` idempotent.
2. **`deleteRecursively()` itself then failed with `AccessDeniedException`.** Root cause: Git marks files under `.git/objects` read-only on Windows. **Fix:** call `path.toFile().setWritable(true)` immediately before each `Files.delete(path)`.

---

## Milestone 5 — Repository Analyzer

**Goal:** Parse a cloned repo's `pom.xml` to detect build tool, framework, Java version, OpenAPI availability, Docker presence, and database driver — each result labeled `DETECTED`, `INFERRED`, or `UNKNOWN`.

**Files created:**
- `analyzer/DetectionStatus.java` (enum), `analyzer/Detected.java` (generic `record<T>` pairing a value with a status), `analyzer/AnalysisResult.java`
- `service/AnalyzerService.java`
- Modified `Project.java` — persisted `detected_build_tool`, `detected_java_version` (via `V3__add_analysis_fields_to_projects.sql`)
- Modified `ProjectController.java` — `POST /{id}/analyze`

**Key logic:**
- Real XML DOM parsing (`DocumentBuilderFactory`/`DocumentBuilder`) instead of regex/string-matching against `pom.xml` — more reliable against comments, whitespace, and nested elements.
- **XXE protection**: `factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)` — disables DTD processing so a malicious `pom.xml` (from an untrusted, arbitrary cloned repo) can't make the parser read local files or make network requests via embedded external entities.
- Deliberately **no `INFERRED` tier for OpenAPI availability** — the analyzer only claims `DETECTED` (explicit `springdoc`/`swagger` dependency found) or `UNKNOWN`, never guesses based on indirect signals like "this looks like a REST API so it probably has OpenAPI."
- Detection rules (summarized): build tool = `pom.xml` exists; framework = `spring-boot-starter-parent` as `<parent>` or any `spring-boot-starter*` dependency; Java version = `java.version`/`maven.compiler.release` properties (DETECTED) or `maven.compiler.source` alone (INFERRED, weaker signal); Docker = `Dockerfile` exists at repo root; DB driver = `postgresql` or `mysql-connector` dependency present.

---

## Milestone 6 — Docker Build Engine

**Goal:** Build a cloned, analyzed project into a Docker image via Java code (not a shell script), streaming live build logs via Server-Sent Events.

**Files created:**
- `build/DockerfileGenerator.java` — generates a Dockerfile parameterized by the analyzer's detected Java version (same shape as Milestone 1's static Dockerfile, now dynamic)
- `service/BuildService.java` — uses the `docker-java` client library
- `V4__add_docker_image_to_projects.sql` — adds `docker_image_id` column
- `controller/BuildController.java` — `GET /{id}/build` (SSE)

**Key logic:**
- Switched from shelling out (Milestone 1/4 style) to the `docker-java` Java client — needed for structured, streaming build output rather than parsing raw process stdout text.
- `SseEmitter` used for the streaming response; the actual build runs on a separate thread (`Executors.newSingleThreadExecutor()`) so the HTTP connection opens immediately while the build (which takes minutes) proceeds in the background, pushing `log` events as Docker reports build progress via `BuildImageResultCallback.onNext()`.
- Built image ID persisted back onto `Project` via a `Consumer<String> onSuccess` callback parameter added to `buildProjectStreaming(...)`.

**Real bugs hit and root causes (this was the hardest debugging stretch of the whole project):**
1. **PowerShell's `curl` alias mangled JSON payloads** with embedded quotes. **Fix:** write JSON to a file and use `-d "@file.json"`, or use `curl.exe` explicitly to bypass the `Invoke-WebRequest` alias.
2. **`docker-java`'s `.withDockerfile(File)` threw `"Dockerfile does not exist"` even though the file was freshly written.** Root cause: a known library quirk in how it resolves absolute `File` paths against the build context. **Fix:** switched to `.withDockerfilePath(String)` (relative path).
3. **Same error persisted even after that fix.** Root cause: `dockerClient.buildImageCmd(baseDirectory)`'s constructor itself validates that a file literally named `Dockerfile` exists in the base directory, *before* any `.withDockerfilePath()` override is honored. **Fix:** name the generated file exactly `Dockerfile` (not `Dockerfile.generated`), and drop the now-unnecessary `.withDockerfilePath()` call entirely.
4. **`HttpHostConnectException: Connect to npipe://localhost:2375`** — docker-java's Windows named-pipe URI handling produced a malformed hybrid npipe/TCP address; confirmed via a diagnostic print that the *configured* value was correct but the *actual connection attempt* used a different, broken one — indicating an internal parsing bug in the `httpclient5` transport module, not a config mistake. Attempted switching to the `zerodep` transport module hit conflicting package-name information across docker-java versions. **Final fix:** abandoned the named pipe entirely — enabled Docker Desktop's **"Expose daemon on tcp://localhost:2375 without TLS"** setting and pointed `docker-java` at plain `tcp://localhost:2375`, which every transport handles reliably. (Explicitly flagged as a local-dev-only setting — must never be replicated on a real deployed server, since it exposes the Docker API unauthenticated.)
