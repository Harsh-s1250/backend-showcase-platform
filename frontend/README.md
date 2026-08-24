# Backend Showcase Platform — Frontend (MVP v2, Phase A)

React + TypeScript, built with Vite. Replaces the static `showcase.html` /
`explorer.html` pages from V1 with a real app — the first piece of PRD §30's
"Technology Recommendation" and §38 Phase A.

## What's here right now

- **`/showcase?projectId=<id>`** — the unified showcase page (PRD §7 "Unified
  Showcase Experience"). Fetches `GET /api/projects/{id}/showcase` (existing V1
  endpoint) and `GET /api/projects/{id}/experience` (new MVP v2 endpoint) and
  renders the right body based on the detected project type:
  - `REST_APPLICATION` -> **Open Application** + **API Explorer** buttons
    (API Explorer is the real, working Swagger UI from V1 -- a full generated
    CRUD UI from the OpenAPI schema is PRD Phase D/E, not built yet, so this
    intentionally does not overclaim).
  - `CONSOLE_APPLICATION` -> an honest "browser terminal not available yet"
    panel, since the interactive stdin/stdout runtime is PRD Phase F/G.
  - `UNSUPPORTED` / `UNKNOWN` -> the fallback described in PRD §36 Scenario C:
    deployed successfully, no automatic interface, but project info/logs/GitHub
    are still there.
  - Always shows the detection reasoning (`DETECTED` / `INFERRED` / `UNKNOWN` +
    the analyzer's explanation) so nothing is presented as more certain than
    it is.
- **`/`** -- a minimal entry point to jump to a project's showcase page by ID.
  There's no owner dashboard here yet (project creation, clone/build/run,
  logs) -- that's still driven by the existing management API directly; this
  app currently only covers the public-facing showcase experience.

## Running it against the platform backend

The backend must already be running per the handoff doc (`mvn spring-boot:run`
on port 8090, Docker daemon reachable, Postgres up).

```bash
npm install
npm run dev
```

This starts Vite on port 5173 and proxies `/api`, `/auth`, `/p`, and
`/explorer.html` to `http://localhost:8090` (see `vite.config.ts`), so the app
behaves as if it were served from the backend itself -- no CORS configuration
needed.

## Building for production

```bash
npm run build
```

Outputs to `dist/`. To serve it from the existing Spring Boot app the same way
`showcase.html`/`explorer.html` are served today, copy the contents of `dist/`
into `backend/platform-backend/src/main/resources/static/` (this isn't wired
up automatically yet -- worth revisiting once the app covers more than the
showcase page).

## Known gaps / next steps

- No route for the owner-side flow (create/clone/analyze/build/run, logs) --
  still raw API calls today.
- `GENERATED_REST_UI` and an actually-connected `BROWSER_TERMINAL` aren't
  implemented on the backend yet, so the frontend code paths for them exist
  but are effectively unreachable until Phase D/E and F/G are done.
- No test setup yet (Vitest + React Testing Library would be the natural
  choice) -- deferred along with backend testing per the handoff's stated
  approach of finishing features before the dedicated testing phase.
