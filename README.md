# Backend Showcase Platform

Turn your backend project into a live, interactive, shareable demo.

The platform connects to a GitHub repository, analyzes the project, builds and deploys it in an isolated Docker environment, and provides an appropriate interactive experience without requiring the developer to build a separate frontend.

```
GitHub OAuth Login
    ↓
Create Project → Clone → Analyze
    ↓
Detect build tool / Java / framework / DB / project type / main class
    ↓
Generate Docker build config → Build image
    ↓
Provision isolated project database (when required)
    ↓
Run project in isolated container
    ↓
Correct interface auto-selected
    ├── REST    → Generated UI / API Explorer
    ├── Console → Browser Terminal
    └── Other   → No interactive interface (honest fallback)
    ↓
Stable public showcase URL
```

## Current Support

**Project types & languages**
* Java, auto-classified as REST, Console, Unsupported, or Unknown
* Maven, with root `pom.xml` detection
* Gradle, via `build.gradle` / `build.gradle.kts` (shallow regex-based parsing — not a full Groovy/Kotlin parser)
* Plain Java (no build tool), compiled and run directly via `javac`/`java`, limited to the JDK standard library
* Automatic build-tool detection cascade: Maven → Gradle → Plain Java → Unknown
* Automatic main-class detection for console apps (explicit `<mainClass>` / `mainClassName` takes priority; ambiguous or missing main classes fail clearly before Docker build rather than guessing)

**Databases**
* PostgreSQL provisioning, one isolated database per project
* MySQL provisioning
* Conditional provisioning — a database is only created when a driver is actually detected
* Automatic `schema.sql` execution on first-ever provisioning (checked in root, `db/`, `sql/`, `database/`)
* Schema warning shown on the showcase page when a DB is provisioned but no `schema.sql` is found
* Automatic JDBC driver fetching for Plain Java projects (Postgres/MySQL) since there's no build tool to resolve dependencies

**Build & deployment**
* Docker-based isolated build and run, with build paths for Maven, Gradle, and Plain Java
* Manifest patching for non-Spring Maven/Gradle console apps so `java -jar` works without a build-declared main class
* Streamed + persisted build logs (SSE), with a friendly human-readable log view alongside the raw Docker output
* Type-aware health checks: HTTP checks for REST apps, container-state checks for console apps, no health check for unsupported/unknown types
* Stable public URLs per project via a reverse proxy (`/p/{projectId}/...`)
* Project lifecycle management: create, clone, analyze, build, run, stop, restart, delete

**Interactive experiences**
* REST APIs / OpenAPI: OpenAPI spec is parsed into a UI schema, generating tabbed, table-based CRUD UI (Add/Edit/Delete) for compatible endpoints
* API Explorer fallback (Swagger UI) for REST APIs that aren't CRUD-shaped (e.g. aggregation endpoints, async job polling, file downloads)
* Java console application browser terminal: real-time xterm.js terminal over WebSocket, attached directly to the running container via the raw Docker Engine socket
* Terminal session isolation — one active session per project, with concurrency-tested exclusive access
* Honest "no interactive interface" fallback for unsupported/unknown project types, rather than pretending support exists

**Platform & access control**
* GitHub OAuth login, with application-level ownership checks (management actions require ownership; showcase pages are intentionally public)
* Owner dashboard for managing projects, viewing build logs, and jumping to the live showcase
* Per-IP rate limiting on public endpoints (login, showcase, proxy, OpenAPI, experience/UI-schema resolvers), with a pluggable store (in-memory by default; Redis-backed store scaffolded but not yet live-verified)

**Testing**
* Automated backend unit/concurrency test suite (`mvn test`, 22/22 passing as of the latest verified state)
* Broader test coverage across `RunService`, `DatabaseProvisionerService`, the controller layer, and a full clone → analyze → build → run integration path
* Independently re-verified frontend production build (`npm run build`)

**Reliability & scale**
* Redis rate-limit store, verified against a live Redis instance
* Redis-backed shared session storage for multi-instance deployment
* Clone size limits and cleanup/expiry for stale workspaces and containers

## Upcoming / Known Gaps

* Full Gradle parser (current parsing is regex/text based)
* Custom React API Explorer (Swagger UI currently used instead)
* Production hardening of Docker access (currently uses unauthenticated Docker TCP)