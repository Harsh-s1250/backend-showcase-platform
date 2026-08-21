# Backend Showcase Platform — Technical Implementation Log (Part 2 of 2)
_Milestones 7–12. Covers: what each milestone built, the reasoning behind design choices, exact files created/modified, and real bugs encountered + root causes._

---

## Milestone 7 — Container Run Engine

**Goal:** Start a built image as a live, resource-limited container with a health check, using `docker-java` (the programmatic equivalent of Milestone 1's manual `docker run`).

**Files created:**
- `service/RunService.java` — `runContainer(projectId, imageId)`
- `V5__add_container_fields_to_projects.sql` — adds `container_id`, `host_port`
- `controller/RunController.java` — `POST /{id}/run`

**Key logic:**
- **Dynamic free-port allocation**: `new ServerSocket(0)` asks the OS for any free port, reads it, closes the socket immediately, then Docker binds to that port moments later. (Small theoretical race window — accepted as adequate for this scale.)
- Container bound to **`127.0.0.1` only**, never `0.0.0.0` — containers are not reachable from outside the host machine, a deliberate security boundary that later became the justification for Milestone 12's reverse proxy.
- Health check = plain TCP reachability (`Socket.connect`), not an HTTP call — chosen because we can't assume every arbitrary project has an Actuator-style health endpoint. (Later found to be an imprecise signal — see Milestone 11 bugs below.)
- Container removed-and-recreated by name (`showcase-run-{projectId}`) on every `/run` call, making it idempotent — safe to call repeatedly on the same project.

---

## Milestone 8 — OpenAPI Explorer

**Goal:** Fetch the OpenAPI spec from a running container and render it as an interactive Swagger UI explorer.

**Files created:**
- `controller/OpenApiController.java` — `GET /{id}/openapi` (server-side proxy fetch of the container's `/v3/api-docs`)
- `src/main/resources/static/explorer.html` — Swagger UI loaded from CDN, pointed at the proxy endpoint via `?projectId=`

**Key logic:**
- The platform **proxies** the OpenAPI spec rather than letting the browser hit the container directly — the container's port is dynamic/internal and shouldn't need to be known by a browsing user, and proxying keeps the container's network surface entirely private (only the platform backend ever talks to it).
- No SSRF risk in the proxy, since the fetched URL (`localhost:{hostPort}`) is built from a value the platform itself generated and stored (Milestone 7), never from user input.

---

## Milestone 9 — Per-Project Database Provisioning

**Goal:** Automatically provision an isolated Postgres database + credentials for any project whose analyzer output detected a database dependency, and inject them into the container.

**Files created:**
- `service/DatabaseProvisionerService.java` — `provisionDatabase(projectId)`
- `V6__add_project_database_fields.sql` — adds `db_name`, `db_username`, `db_password` to `projects`
- Modified `RunService.runContainer(...)` — accepts `DbCredentials`, injects `SPRING_DATASOURCE_URL`/`_USERNAME`/`_PASSWORD` as container environment variables
- Modified `RunController.run(...)` — provisions once per project, reuses on subsequent runs

**Key logic:**
- **Database-per-project on the shared Postgres instance** (not a separate Postgres container per project) — genuine isolation (Postgres databases don't cross-query by default) without the operational overhead of one Postgres container per showcased project.
- Fresh, `SecureRandom`-generated password per project — a compromised container only ever exposes its own database's credentials, not every project's.
- Credentials passed via `docker run` environment variables (not baked into the image) — never written into a layer that could be inspected via `docker history` or pushed to a registry.
- `host.docker.internal` used as the hostname so containers can reach the host machine's Postgres instance.
- String-concatenated SQL used deliberately (not parameterized) for `CREATE USER`/`CREATE DATABASE`, because JDBC cannot parameterize SQL *identifiers* — explicitly justified as safe only because the identifiers are built entirely from a UUID the platform generated itself, never from user input.

**Real bugs hit and root causes (five distinct Postgres/environment issues in sequence):**
1. **`permission denied to create role`** — `showcase_user` (created in Milestone 3, scoped only for the platform's own DB) lacked `CREATEROLE`. **Fix:** `ALTER USER showcase_user CREATEROLE CREATEDB;`
2. **`role "user_..." already exists`** — a partially-failed earlier attempt left an orphaned role blocking retries; provisioning was not idempotent. **Fix (interim):** manually dropped the leftover role/database in pgAdmin. **Fix (permanent):** rewrote `provisionDatabase()` to check `pg_roles`/`pg_database` first and only `CREATE` if not already present (`ALTER USER ... WITH PASSWORD` if it already exists).
3. **`must be able to SET ROLE "user_..."`** — Postgres 16+ requires the creating role to have explicit membership in a role before it can be set as a database `OWNER`; `CREATEROLE` alone isn't sufficient. **Fix:** added `GRANT {username} TO {adminUsername};` immediately after user creation, before the `CREATE DATABASE ... OWNER` step.
4. **The idempotency and role-membership fixes were prescribed in conversation but never actually applied to the file** — a repeat of the same class of "described but not implemented" issue seen elsewhere. Caught by re-pasting the file's actual contents and diffing against what was intended, rather than assuming the fix had landed.
5. **After all provisioning fixes worked, the actual DB-backed endpoint returned "Empty reply from server."** Root cause: not a bug — the container was still mid-startup (JPA/Hibernate + live Postgres connection took over 100 seconds on this app), and the request arrived before Spring Boot finished booting. Confirmed via `docker logs` showing the app was still initializing. Resolved by simply waiting and retrying; formally addressed in Milestone 11 by widening the health-check window.

---

## Milestone 10 — GitHub OAuth + Ownership Enforcement

**Goal:** Replace manual GitHub URL entry with real OAuth login, tie projects to authenticated users, and enforce that users can only act on their own projects.

**Files created:**
- `entity/User.java`, `repository/UserRepository.java`
- `V7__add_users_and_project_ownership.sql` — `users` table (`github_id` unique, `access_token`, etc.) + `projects.owner_id` FK
- `service/GitHubOAuthService.java` — authorization URL builder, code-for-token exchange, GitHub profile fetch, find-or-create `User`
- `controller/AuthController.java` — `/auth/login`, `/auth/callback`, `/auth/me`
- `config/SecurityConfig.java` — minimal `HttpSecurity` bean (session-based, CSRF handled manually via OAuth `state`)
- `service/CurrentUserService.java` — `requireCurrentUser(session)`, `requireOwnership(user, projectOwnerId)`
- `exception/ProjectNotFoundException.java` (new, proper 404 exception, replacing a placeholder generic `RuntimeException`)
- Modified every existing action controller (`ProjectController`, `BuildController`, `RunController`, `OpenApiController`) to require `HttpSession session`, call `requireCurrentUser`/`requireOwnership` before doing anything

**Key logic:**
- OAuth **Authorization Code flow** (not a pasted Personal Access Token) — narrower scope, revocable from GitHub's side, standard UX pattern.
- **CSRF protection via the OAuth `state` parameter**: a random value is generated and stored in the server-side session before redirecting to GitHub, then verified on callback — without this, an attacker could link their own GitHub account into a victim's session (a well-documented OAuth attack class).
- `github_id` (immutable numeric ID) used as the unique key for `User`, not `github_username` (which can be changed).
- Server-side sessions chosen over JWTs for MVP simplicity, given the whole platform is currently one Spring Boot monolith with no separate frontend SPA.
- **Explicit, deliberate scope boundary:** this milestone does OAuth + ownership *enforcement*, not a polished "my projects" dashboard UI.

**Real bugs hit and root causes:**
1. **Pasted a live Client Secret directly into chat.** Addressed by regenerating the secret immediately and moving all secret handling to a gitignored `application-local.properties` file (loaded via `spring.profiles.include=local`), never typed into chat again.
2. **`Could not resolve placeholder 'github.oauth.client-id'`.** Root cause: `spring.profiles.include=local` and the new `application-local.properties` file were both accidentally added to the *wrong* Maven project (`sample-task-api` instead of `platform-backend`). Fixed by moving both to the correct project and reverting the accidental change in `sample-task-api`.
3. **Missing `generateState()` method** — referenced in `AuthController` but its body was never actually included when the file was first written. Straightforward addition once caught.
4. **Compilation errors from missing imports** (`CurrentUserService`, `User`, `HttpSession` not imported in `ProjectController`) and an **unrelated pre-existing bug surfaced by the same rebuild**: `BuildController` was still calling `BuildService.buildProjectStreaming()` with 3 arguments after Milestone 6 had changed it to require a 4th (`onSuccess` callback) — this had been silently broken since Milestone 6 and only surfaced now because a full rebuild was triggered.
5. **Fake-project-ID test returned `500` instead of the expected `404`.** Root cause: every controller's "not found" case used a generic `new RuntimeException(...)`, which `GlobalExceptionHandler` had no specific handler for. **Fix:** introduced `ProjectNotFoundException` with a dedicated `@ExceptionHandler` returning `404`, replacing the placeholder across all four controllers.

---

## Milestone 11 — Lifecycle Controls (Stop / Restart / Delete)

**Goal:** Add real stop, restart, and delete actions for a project's container, closing the "no cleanup" gap flagged repeatedly since Milestone 4.

**Files modified:**
- `service/RunService.java` — added `stopContainer()`, `removeContainer()`, `restartContainer()`
- `controller/RunController.java` — added `POST /{id}/stop`, `POST /{id}/restart`, `DELETE /{id}` (with a name-confirmation body as a safety check against accidental destructive calls)

**Key logic:**
- `/stop` and `/restart` are cheap/reversible (image, database, workspace all untouched); `/delete` is destructive/irreversible — deliberately kept as separate actions rather than one "remove" endpoint, so a temporary stop can never accidentally cascade into losing a database.
- `/restart` re-inspects the container via `dockerClient.inspectContainerCmd()` to read back its actual host port binding rather than assuming the previously stored value is still accurate.
- `/delete` requires the caller to echo back the project's exact name in the request body — a lightweight but deliberate confirmation step, since this is the first genuinely irreversible action in the platform.
- Ownership checks (`requireCurrentUser`/`requireOwnership`) applied identically to all three new endpoints, following the Milestone 10 pattern.
- **Explicitly incomplete, by design of scope, not oversight:** `/delete` removes the Docker container and the `Project` database row, but does **not** yet delete the cloned workspace directory on disk or drop the project's provisioned Postgres database — flagged as real remaining work, not silently skipped.

**Real bugs hit and root causes:**
1. **`403 Forbidden — Not authenticated`** on the first stop attempt. Root cause: restarting the Spring Boot app (to load the new endpoints) cleared all in-memory sessions (Tomcat's default session store). Not a bug — an inherent limitation of in-memory sessions, worth remembering as permanent behavior during development. **Resolved by simply logging in again.**
2. **`curl: (52) Empty reply from server`** on `/api/health` shortly after a successful `/restart`. Root cause: confirmed via `docker logs` that this specific DB-backed app took **106+ seconds** to fully boot (JPA/Hibernate initialization against a live Postgres connection, likely slowed further by the 0.5 CPU resource limit from Milestone 1/7) — far longer than the original 30-second health-check window (`15 attempts × 2000ms`). This was a real, previously-latent gap in the health check design (TCP-reachable ≠ actually ready to serve requests), first suspected back in Milestone 9. **Fix:** widened `HEALTH_CHECK_ATTEMPTS` to 60 (120 seconds total).

---

## Milestone 12 — Public Routing / Reverse Proxy (Design Only — Not Yet Implemented)

**Goal (planned):** Give every running project a stable, path-based URL (`/p/{projectId}/...`) served through the platform itself, instead of exposing a project's dynamically-assigned Docker port directly.

**Status:** Design and reasoning fully worked out; **no code has been written yet.** `controller/ProxyController.java` does not exist.

**Planned key logic:**
- A Spring controller-based reverse proxy (using `RestClient`, consistent with the OpenAPI proxy from Milestone 8) rather than introducing Nginx/Traefik as new infrastructure — deferred deliberately, same reasoning as the original architecture discussion (path-based routing is simpler for MVP than subdomain DNS/TLS).
- Path-based routing (`/p/{id}/...`) chosen over subdomain-based routing (`{id}.platform.dev`) specifically to avoid needing wildcard DNS/TLS infrastructure.
- Identified as the piece that "activates" the security decision made all the way back in Milestone 7 (containers bound to `127.0.0.1` only) — once this proxy exists, the platform's own port becomes the *only* thing that ever needs to be publicly reachable in a real deployment.
- Ownership/access-control question on the proxy route was identified as a genuinely open design decision at the point work stopped (a showcased project's whole purpose is being viewable by others, which is in tension with the ownership-enforcement pattern established in Milestone 10) — **not yet resolved.**
