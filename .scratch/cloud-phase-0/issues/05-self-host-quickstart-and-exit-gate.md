# 05 — Self-host quick start and the scripted Phase 0 exit gate

**Parent:** `docs/CLOUD_PLAN.md`, "Phase 0" exit criterion + "Self-hosting and deployment".

**What to build:** A self-hoster clones the repo, runs one compose command, and follows a short
README to a working sign-in and their first library folder on disk. The same flow is checked
in as a script that CI runs against the freshly built container on every push, so Phase 0's
exit criterion stays true as Phases 1–6 land on top of it rather than being something that was
demonstrated once.

Concretely:

- `cloudServer/docker-compose.yml`: the server image, a bind-mounted `data/` volume, the
  minimal env (data dir, base URL), commented-out SMTP block, and a note on where TLS goes.
  A companion `Caddyfile`/Traefik snippet as the reverse-proxy example the plan promises.
- Self-host guide (`cloudServer/README.md`, linked from the top-level README): quick start,
  full env reference (consolidating what tickets 01–04 each documented), the backup story
  ("back up `data/`; the change feed and per-library indexes rebuild"), the escape hatch
  ("point the desktop app at `data/libraries/<id>/`"), and the CLI reference
  (`npn-cloud auth code`, and whatever else exists by now).
- The exit-gate script: builds (or takes) the image, starts it with no SMTP and a temp data
  dir, then drives the Phase 0 story with `curl` and the CLI — request code, read code via the
  CLI, verify, create a library, assert `data/libraries/<id>/.npn/` exists on the volume,
  assert `/health` — and exits non-zero on any deviation. It also runs the single-user-mode
  variant. Shellcheck-clean, since CI already lints shell. Lives under `cloudServer/` and is
  wired into the CI job after `docker build`.
- Top-level `README.md` gains a short "Cloud companion (optional)" section pointing at the
  guide, making clear the apps remain fully serverless.

**Blocked by:**
- 03 — Create a library and find its folder on disk
- 04 — Optional SMTP delivery, single-user mode, and auth rate limits

**Status:** ready-for-agent

- [ ] `docker compose up` in `cloudServer/` from a clean checkout yields a server whose
      `/health` answers and whose `data/` appears on the host.
- [ ] The exit-gate script passes locally against the compose stack and in CI against the
      CI-built image, in both default and single-user modes; breaking any step in the flow
      (e.g. renaming an endpoint) fails CI.
- [ ] The guide's quick start can be followed verbatim from a clean machine with only Docker
      installed; every `NPN_CLOUD_*` variable the server reads is listed in the env reference
      (a test or script asserts the two sets match).
- [ ] The escape hatch is demonstrated once in the guide: opening `data/libraries/<id>/` in
      the desktop app shows an empty library.
- [ ] Shellcheck passes on the new scripts; the CI workflow ordering in `AGENTS.md` is
      updated to include the container smoke/exit-gate step.
