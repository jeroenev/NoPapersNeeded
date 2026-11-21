# Contributing

Thanks for your interest in No Papers Needed. Bug reports, ideas and pull requests are welcome.

## Before you start

- **Bugs and ideas:** open an issue first, using one of the templates. For anything bigger than
  a small fix, please discuss it in an issue before writing code, so the work fits the design.
- **Security problems:** never in a public issue; see [SECURITY.md](SECURITY.md).

## Working on the code

- [DEVELOPMENT.md](DEVELOPMENT.md): requirements, building, tests and packaging.
- [ARCHITECTURE.md](ARCHITECTURE.md): the module map, the data flow and where to look for
  common tasks.
- [CONTEXT.md](CONTEXT.md): what the domain terms mean.
- [AGENTS.md](AGENTS.md): the rules the codebase keeps (architecture invariants, conventions,
  lint). They apply to every change, whether or not an AI agent wrote it.

Install the repository's Git hooks once with `mise hooks-install`. The pre-commit hook runs
formatting, lint and the desktop tests.

## Pull requests

- Keep a pull request to one change, with tests for the behaviour it adds or fixes.
- New user-facing text needs a translation in all six locales.
- CI must pass. CI on a pull request from a fork waits until a maintainer approves the run.

By contributing, you agree that your contribution is licensed under the
[Apache License 2.0](LICENSE).
