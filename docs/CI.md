# CI

GitHub Actions, `.github/workflows/ci.yml`. Runs on every push to `main`, on pull requests, and by hand (Actions tab >
CI > Run workflow). Pushes that only touch Markdown, `docs/` or `run/` are skipped. Nothing is deployed anywhere.

| Job | Does | Fails when |
|---|---|---|
| Plugin build and unit tests | `./gradlew build` at the root: plugin, self-test plugin, MockBukkit unit tests | Compile error or a unit test fails |
| Client build and unit tests | `fabric-client/gradlew build`: the Fabric mod and `DraftTest` | Compile error (usually a Minecraft GUI class moved) or a test fails |
| Self-test on a real Paper server | Downloads the pinned Paper build, checks its SHA-256, runs `ci/run-selftest.sh` | The report doesn't end `SELFTEST PASSED`, or the server hangs (10 minutes) |

Each run keeps the jars as artifacts (`logic-plugin`, `paper-selftest`, `logic-blocks-client`) plus
`selftest-report` (report and server log). The self-test report is also printed on the run's summary page.

## Pins

The workflow's `env` block: `JAVA_VERSION`, `PAPER_VERSION`, `PAPER_BUILD`, `PAPER_SHA256`. To move to a new
Paper build, take the hash of the jar you tested locally (`certutil -hashfile paper-<v>-<b>.jar SHA256` on Windows,
`sha256sum` elsewhere) and change all three Paper values together.

## Running the self-test by hand

On Linux or in Git Bash, after `./gradlew build`:

```
ci/run-selftest.sh path/to/paper-26.3-211.jar /tmp/selftest-run
```

The second argument must be a folder that doesn't exist yet. Without internet access, add
`PAPER_SEED_DIR=path/to/an/existing/paper/server` so its `cache/`, `versions/` and `libraries/` are reused.

## Not yet

Release jars on tags (planned). The real client is not launched in CI; UI checks stay manual (`docs/TEST_PLAN.md`).
