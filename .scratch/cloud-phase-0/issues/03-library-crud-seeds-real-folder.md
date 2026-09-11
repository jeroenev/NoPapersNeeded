# 03 — Create a library and find its folder on disk

**Parent:** `docs/CLOUD_PLAN.md`, "Phase 0" + "Endpoints" + "Server data model" + "Security
notes". This ticket completes the plan's Phase 0 exit criterion.

**What to build:** A signed-in user creates a library from `curl`, lists it, and finds a *real
library folder* for it under the server's data dir — `data/libraries/<id>/` with the `.npn/`
skeleton — that the desktop app could open directly. The plan's whole premise is that the
server's copy is an ordinary library folder, so the seeding goes through `core`'s own path
layout and filesystem adapter rather than a server-side reimplementation. Another user cannot
see, touch, or even confirm the existence of the library.

Concretely:

- Endpoints (DTOs in `:cloudApi`): `GET /v1/libraries` (the caller's own, non-deleted),
  `POST /v1/libraries {name}` → `{id, name, storageBytesQuota, createdAt}`,
  `GET /v1/libraries/{lib}`, `DELETE /v1/libraries/{lib}` (soft delete: `deleted_at` set,
  folder kept — the escape hatch says a self-hoster can always recover the folder; physical
  removal is an admin concern for a later phase). All routes require the bearer principal from
  ticket 02 and are authorized against ownership: a library that exists but belongs to someone
  else answers exactly like one that does not exist.
- Tables: `libraries(id, user_id, name, storage_bytes_quota, created_at, deleted_at)` with the
  quota defaulted from an env-configured operator default, and
  `library_leases(library_id, node_id, heartbeat_at, ttl)`. On the v1 single node the server
  takes the lease for every library it creates or finds at startup and heartbeats it; nothing
  else reads it yet. It exists now so the "one node owns a library's folder at a time"
  invariant has a home before anything depends on it.
- Seeding: the library folder is created with `core`'s JVM `LibraryFileSystem` over
  `data/libraries/<id>/`, and the `.npn/` skeleton comes from `NpnPaths` constants (journal
  dir, ocr dir, trash dir, tmp dir) — no server-side string literals for the layout. The per-
  library *index* db is *not* created here (that is Phase 4, when a headless session opens the
  folder); the folder alone is the deliverable.
- Library ids are unguessable (random, not sequential), since they appear in URLs and storage
  paths.
- A per-user library count cap (env, generous default) so an open-registration self-host cannot
  be filled with empty folders.

**Blocked by:** 02 — Sign in from `curl` with a one-time code and hold a device token.

**Status:** ready-for-agent

- [ ] Phase 0 exit criterion, from `curl` against a server with no SMTP: sign in (via the
      logged code), `POST /v1/libraries`, then `ls data/libraries/<id>/.npn/` shows the
      skeleton directories; `GET /v1/libraries` lists it.
- [ ] The seeded folder opens as a valid, empty library in `core`: a server test opens the
      seeded folder with the JVM session wiring from `core` and asserts zero documents and no
      errors. (This is the "desktop app can open it directly" promise, tested without the UI.)
- [ ] User B's `GET`/`DELETE` on user A's library id returns the same response as a random
      non-existent id; user B's list does not include it.
- [ ] Soft-deleted libraries disappear from list/get but their folder remains on disk.
- [ ] The library cap is enforced with a `:cloudApi` error code; the quota default lands on
      the row and in the create response.
- [ ] After restart the server re-takes the lease for every existing library; a test asserts a
      lease row per library with this node's id.
- [ ] `cloudServer/README.md` documents the data-dir layout (`data/libraries/<id>/`, accounts
      db location) and the recovery escape hatch.
