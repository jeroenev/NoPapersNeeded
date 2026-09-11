# 01 — Server skeleton answers `/health` from a Docker container, under CI

**Parent:** `docs/CLOUD_PLAN.md`, "Phase 0 — Protocol and server skeleton".

**What to build:** An operator can build the npn-cloud server from a clean checkout, run it
as a container, and get a `200` from `/health` that reports the protocol version and that the
SQLite database is reachable. From the repo's side, the two new modules exist and every
quality gate the existing modules pass (spotless, detekt, tests, build) runs over them too, so
later Phase 0 tickets add endpoints to a module that is already green in CI.

Concretely:

- `:cloudApi` — a KMP module (`commonMain` + `desktop` + android targets, matching `:core`)
  holding what client and server must agree on and therefore may not drift: the protocol
  version constant, the error-code vocabulary (as a sealed/enumerated type, including the
  stale-cursor and protocol-version errors the plan says must exist from day one even though
  nothing raises them yet), and the first DTOs (`/health` response, the standard error
  envelope). kotlinx-serialization only; no Ktor dependency.
- `:cloudServer` — a plain JVM module depending on `:core` (desktop variant) and `:cloudApi`.
  Ktor server; configuration read from `NPN_CLOUD_*` environment variables with a typed config
  object and sensible defaults (data dir, bind port, db). SQLite persistence with a migration
  runner that creates the schema on first start — the accounts db lives under the data dir,
  never inside any library folder. `/health` is the one endpoint.
- **Persistence decision.** The plan defers "Exposed vs SQLDelight vs plain JDBC" to Phase 0;
  this ticket decides it and records the reasoning in a short ADR-style note in the module's
  README. Constraints: SQLite must stay first-class (self-host default), Postgres must be
  addable later without rewriting queries, and the choice must not pull the server's schema
  into Room (Room stays the *per-library index*, opened through `core`, and is a separate
  concern from the accounts db).
- Packaging: a fat jar task and a `Dockerfile` (JRE 17 + `tesseract-ocr` + the jar, even though
  OCR is used only from Phase 4 — the image shape is fixed now so it does not change under
  self-hosters later). `docker build` works from the repo root.
- CI + task wiring: the hard-coded module lists in the CI workflow and `mise.toml` gain the new
  modules (spotless, detekt, tests, jar build, and a `docker build` smoke step). A
  `detekt-baseline.xml` for each new module is *empty* — no grandfathered findings in new
  code. `ARCHITECTURE.md` and `AGENTS.md` mention the new modules in their module lists.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] `./gradlew :cloudApi:allTests :cloudServer:test` pass; both modules pass `spotlessCheck` and
      `detekt` with empty baselines.
- [ ] `./gradlew :cloudServer:<fatJar task>` produces a runnable jar; `java -jar … ` with
      `NPN_CLOUD_DATA_DIR` pointing at an empty dir starts, creates the accounts db there, and
      serves `GET /health` → `200` with `{protocolVersion, db: "ok"}` (shape defined in
      `:cloudApi`).
- [ ] `docker build` from the repo root succeeds and `curl` against the running container
      returns the same `/health` body; the image contains `tesseract`.
- [ ] Unknown routes return the standard error envelope from `:cloudApi`, not Ktor's default
      page.
- [ ] A server test boots the application in-process (Ktor test host) against a temp data dir
      and asserts `/health`; a `:cloudApi` test round-trips the DTOs through
      kotlinx-serialization.
- [ ] CI workflow and `mise.toml` cover the new modules; `mise test-all` / `mise check` include
      them.
- [ ] The persistence-layer choice is written down (what, why, how Postgres is added later)
      in `cloudServer/README.md`.
