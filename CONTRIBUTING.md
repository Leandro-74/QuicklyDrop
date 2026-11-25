# Contributing

## Requirements

- JDK 21.
- Android SDK 35 (platform and build-tools).

## Build and test

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :protocol:test :core:testDebugUnitTest :app:testDebugUnitTest \
  :lab:testDebugUnitTest :diag:testDebugUnitTest
./gradlew :app:assembleDebug
```

Run `./gradlew :app:lintDebug` before touching UI or manifest code.

## Branch workflow

1. Start from an up-to-date `main`.
2. Create one branch per logical change (`feat/...`, `fix/...`, `refactor/...`,
   `chore/...`, `docs/...`).
3. Keep commits atomic and write them in English using Conventional Commits.
4. Run the full test suite before each commit.
5. Push the branch, wait for CI, merge with a fast-forward into `main`, then delete the
   branch locally and on the remote.

Do not mix refactors, features and unrelated documentation in the same branch.

## Code style

- New code comments, docstrings and user-facing strings are in English.
- `:protocol` must not import Android APIs.
- `:core` must not contain UI code.
- `:app` must not open sockets or touch MediaStore directly; use `:core`.
- Never log SSID, passphrase, file names or key material.
- Put secrets only in `.env`; keep `.env.example` fictitious.

## Releases

The version lives in `app/build.gradle.kts`. Release signing reads `.env` locally and
GitHub Actions secrets in CI. Push a `v*` tag to run the release workflow.
