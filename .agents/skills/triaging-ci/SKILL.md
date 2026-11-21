---
name: triaging-ci
description: Diagnose a failing GitHub Actions run (CI, Windows build, CodeQL, release, secret scan) before changing code, reproduce it locally, and fix the cause. Use when a pull request check is red, a workflow fails, a release draft builds nothing, or the user asks why CI failed.
---

# Triaging CI

Identify the run, commit and event. Read the first actionable error in the failing job
(`gh run view <id> --log-failed`). Trace missing artifacts back to their producing step.
Reproduce with the failing task and options on the affected platform. For release gates,
use AGENTS.md and the workflow's CI status check.

## Platform clues

- **Windows cache**: a running Gradle daemon holds `.lock` files that Windows won't let the cache
  archiver read. The run then reports `Entries: 0 restored, 0 saved` and every Windows run starts
  cold. Keep a final `./gradlew.bat --stop` step (`if: always()`) before setup-gradle's cleanup.
- **Windows fonts**: PDFBox's first font lookup on a fresh host can take about two minutes. Tests
  get a longer timeout on Windows hosts; don't "fix" that by shortening the test.
- **Windows timing**: tests that race the folder watcher pass on Linux and fail on a slow runner.
  Write the other device's file before the PDF (the Windows auto-match race), don't sleep.
- **Windows filesystem**: moves need `NoReplaceMove`'s Windows branch (`MoveFileExW`); an
  "exclusive rename requires Linux or Android" error is a real app bug, not a test problem.
- **Windows packaging**: a Tesseract check that runs against `build/compose/binaries/main/app`
  needs `:desktopApp:createDistributable`, or the folder never exists.
- **CodeQL**: if analysis was killed, check runner memory and the Gradle daemons.

## Verify

For a code or workflow fix, check the new run on the fixed commit. For a confirmed transient
runner failure, rerun the failed jobs on the same SHA with `gh run rerun <id> --failed`.
Report the run, cause and verifying job.
