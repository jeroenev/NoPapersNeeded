# 02 — Sign in from `curl` with a one-time code and hold a device token

**Parent:** `docs/CLOUD_PLAN.md`, "Phase 0" + "Sync protocol → Auth" + "Server data model".

**What to build:** A person with only `curl` and the server log can sign in to a fresh
npn-cloud server with no SMTP configured: request a code for their email, read the code from
the server log (or the `npn-cloud auth code <email>` CLI), exchange it for a short-lived access
JWT plus a long-lived device refresh token, refresh the access token later, list the devices
they have signed in from, and revoke one — after which that device's refresh fails while the
others keep working. The first user to verify becomes admin; after that, whether new emails may
register is an operator switch.

Concretely:

- Endpoints (request/response DTOs and error codes live in `:cloudApi`):
  `POST /v1/auth/code {email}`, `POST /v1/auth/verify {email, code, deviceName}` →
  `{accessToken, refreshToken, user}`, `POST /v1/auth/refresh {refreshToken}` → new access
  token, `GET /v1/devices`, `DELETE /v1/devices/{id}`. Responses never reveal whether an email
  is registered (same answer for known and unknown emails on `/auth/code`).
- Tables: `users(id, email, created_at, disabled, admin)`,
  `login_codes(email, code_hash, expires_at, attempts)`,
  `device_tokens(id, user_id, name, token_hash, created_at, last_seen, revoked)`. Codes are
  short-lived, single-use, and rate-bounded by attempts; codes and refresh tokens are stored
  hashed. Refresh tokens are revocable-only (no forced expiry), per the plan.
- Code delivery is behind a small interface with one implementation in this ticket: write to
  the server log at a clearly labelled level. (SMTP is ticket 04.) The `npn-cloud auth code
  <email>` CLI subcommand reads the current unexpired code for that email from the db, so a
  self-hoster does not have to grep logs.
- Access JWT: short-lived, signed with a server secret that is generated on first start and
  persisted under the data dir when not supplied via env, so a restart does not log every
  device out. An auth plugin/route scope turns a bearer token into a principal that later
  tickets' routes can require.
- Operator switches via env: registration open/closed (closed = only existing users can
  request codes; still the same non-revealing response), first-user-is-admin.

**Blocked by:** 01 — Server skeleton answers `/health` from a Docker container, under CI.

**Status:** ready-for-agent

- [ ] With no SMTP configured: `curl` the full flow — code → verify → authenticated request →
      refresh → list devices → revoke → refresh with the revoked token fails with the
      `:cloudApi` error code for a revoked token; a second device's refresh still works.
- [ ] `npn-cloud auth code <email>` prints the pending code; after the code is used or expired
      it prints nothing and exits non-zero.
- [ ] A wrong code fails; after the attempt cap the code is dead even if the right one is then
      supplied. An expired code fails the same way.
- [ ] `/auth/code` returns an identical response for a registered and an unregistered email,
      with registration open and with it closed; with registration closed an unknown email
      gets no code created.
- [ ] The first verified user has `admin = true`; the second does not.
- [ ] Restarting the server keeps existing access tokens valid (persisted signing secret) and
      existing refresh tokens usable.
- [ ] Tokens and codes in the db are hashes — a test asserts the raw values never appear in
      any row.
- [ ] `ARCHITECTURE.md`/`cloudServer/README.md` describe the auth flow and the env switches.
