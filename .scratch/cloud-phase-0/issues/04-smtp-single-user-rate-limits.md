# 04 — Optional SMTP delivery, single-user mode, and auth rate limits

**Parent:** `docs/CLOUD_PLAN.md`, "Sync protocol → Auth" + "Security notes" + "Self-hosting
and deployment".

**What to build:** The three operator-facing pieces of Phase 0 auth that the plan calls for
but that a `curl`-and-log sign-in does not need. A hosted operator configures SMTP and users
get their codes by email; a solo self-hoster sets one env var and never sees an account at
all; and neither deployment can have its auth endpoints hammered. Nothing changes for a server
with none of this configured — the log/CLI path from ticket 02 stays the default.

Concretely:

- **SMTP delivery** as a second implementation of ticket 02's code-delivery interface,
  selected when `NPN_CLOUD_SMTP_*` (host, port, user, password, from, STARTTLS/TLS) is set.
  Plain-text email with the code and its validity window; the log fallback is *not* also
  written when SMTP is configured (a code in the log of a hosted server is a leak). Delivery
  failure surfaces as a server-side error log and the generic non-revealing response to the
  caller — never a stack trace in the response. Tested against an in-process fake SMTP sink,
  never a real mail server.
- **Single-user mode** — `NPN_CLOUD_SINGLE_USER=1`: the server has exactly one implicit admin
  user, `/auth/code` and `/auth/verify` are disabled (a clear error code), and a static bearer
  token from env (or generated on first start and printed once / retrievable via the CLI) is
  the only credential. Everything downstream (devices, libraries) works unchanged because the
  principal is the same shape. The config refuses to start with single-user mode *and*
  registration-open set together — the combination is meaningless and hides a misconfiguration.
- **Rate limits** on `/auth/code`, `/auth/verify` and `/auth/refresh`, keyed per client
  address and, for `/auth/code`, per email as well, with `429` and a `Retry-After` header and a
  `:cloudApi` error code. Limits are env-configurable with defaults that a real person never
  hits. The limiter's state is in-memory (single node in v1), and that limitation is written
  down where a multi-node deployment would look for it.

**Blocked by:** 02 — Sign in from `curl` with a one-time code and hold a device token.

**Status:** ready-for-agent

- [ ] With SMTP env set, requesting a code produces one email at the fake sink containing the
      code, and the code does not appear in the server log; the emailed code verifies.
- [ ] With SMTP unset, behaviour is byte-identical to ticket 02 (existing tests unchanged).
- [ ] With a broken SMTP host, `/auth/code` still returns the generic response and the failure
      is logged.
- [ ] `NPN_CLOUD_SINGLE_USER=1`: `/auth/code` and `/auth/verify` return the disabled error
      code; the static token authenticates; `/v1/libraries` works end to end with it; the
      server refuses to start when registration-open is also set.
- [ ] Exceeding the limit on each of the three auth endpoints yields `429` + `Retry-After`;
      a different client address is unaffected; the window resets.
- [ ] `cloudServer/README.md` gains an env reference covering SMTP, single-user mode and the
      rate-limit knobs, and states the single-node limitation of the limiter.
