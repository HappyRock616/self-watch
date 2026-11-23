# Contributing to Self Watch

Thanks for helping. Self Watch is small, private and free. These rules keep it that way.
The full build plan and module rules are in [docs/build-plan/README.md](docs/build-plan/README.md).

## 1. Write everything from scratch (the no-copy rule)

- **Never copy code** from Gadgetbridge, Pulse, Internet Helper, Garmin's SDK, Stack Overflow
  answers, AI output of unknown origin, or anything else. Not a function, not a table, not a test.
- References are **read, never copied**. Reading a reference project to learn how a protocol works
  is fine; then close it and write your own code.
- **Never open, unpack or decompile** Garmin's closed Connect IQ SDK, the Garmin Connect app, or
  any other closed-source app or firmware.
- The `references/` folder is not part of the repo and must never be committed.

### Reference comments

When a protocol fact (a message id, a field order, a quirk) came from a reference, say where:

```kotlin
// Ref: Gadgetbridge 160beab41 <path> (<what>)
```

Format: `// Ref: <project> <commit> <path in that project> (<what you learned>)`. The comment
records the source of a fact; it is not permission to copy the code around it.

## 2. Tests are part of every change

- Kotlin-only modules (`core-model`, `fit`, `garmin`): JUnit 4 tests on the computer.
- Android modules: JVM tests for logic, Robolectric where Android classes are needed, instrumented
  tests only for Bluetooth, SQLite specifics and the camera.
- Every bug fix adds a test that fails without the fix.
- Parsers of outside input (FIT files, imports, watch messages) need tests that feed them corrupt
  data; it must never crash the app.
- `./gradlew build` must pass before you open a pull request.

## 3. Allowed libraries only

The only libraries allowed are the ones in the catalog in
[WP-01](docs/build-plan/WP-01-build-ci.md) and `gradle/libs.versions.toml`. Versions live only in
`gradle/libs.versions.toml`.

- Adding a library, or a new artifact of an allowed library, needs HappyRock's approval first
  and a pull request that says why. Reviews reject anything else.
- Never allowed: Hilt/Dagger, Room, greenDAO, Compose, OkHttp, Retrofit, Gson, Moshi, Navigation,
  WorkManager, MPAndroidChart, Google Play Services, Firebase, any analytics or crash reporter,
  any library with native code.
- Only the `net` module may use the network (`java.net` HTTP/sockets, `javax.net.ssl`).

Every dependency's SHA-256 is pinned in `gradle/verification-metadata.xml`; a changed or tampered
artifact fails the build. After an approved version change, regenerate it and review the diff:

```sh
./gradlew --write-verification-metadata sha256 build :app:licenseCheck --no-configuration-cache
```

Platform-specific artifacts (aapt2 for linux/osx/windows) only get the current machine's checksum;
add the other platforms' sha256 by hand, as the existing aapt2 entry shows.

CI enforces part of this: the release APK must stay under the size budget and contain no native
code (`tools/size/`), every shipped library must have an allowed license (`:app:licenseCheck`),
and network APIs outside `net` fail the build (`forbiddenNetworkApi`).

## 4. License headers

Every source file starts with a license header.

App modules (everything except `link-client`), AGPL-3.0-or-later:

```kotlin
/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
```

`link-client` (used by other apps), Apache-2.0:

```kotlin
/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in this module.
 */
```

## 5. Pull requests

- All changes reach `main` through pull requests. Nobody pushes to `main` directly.
- The maintainer agent (the automated helper that keeps the repo tidy) **only opens pull
  requests**: it never merges, never pushes to `main`, and never changes CI, signing or release
  settings on its own. A human maintainer approves every merge.
- A pull request says what changed, how it was tested, and, if the APK grew by more than 100 KB,
  why.

## 6. Style

Official Kotlin style, 4 spaces, LF line endings (`.editorconfig`). Explicit types on public API,
no `!!` in production code, no reflection, no hidden Android APIs. All user-facing text in
`strings.xml`. See the module rules in the build plan for the rest.

## Running the checks locally

```sh
./gradlew build                                   # compile, unit tests, lint, forbiddenNetworkApi
./gradlew :app:licenseCheck --no-configuration-cache
./gradlew :app:assembleRelease
sh tools/size/check.sh app/build/outputs/apk/release/app-release-unsigned.apk
```
