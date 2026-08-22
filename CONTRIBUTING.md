# Contributing

## What you need

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 | The Gradle daemon is pinned to it by `gradle/gradle-daemon-jvm.properties` |
| Android SDK | Platform 36, build-tools 36+ | `compileSdk 36`, `targetSdk 36`, `minSdk 28` |
| .NET SDK | 10.0 | Only needed for `desktop_editor` |

The Android build resolves Gradle itself through the wrapper, and the wrapper checks the
distribution against a SHA-256 in `gradle/wrapper/gradle-wrapper.properties`. Do not remove that
checksum.

Point the build at your SDK with a `local.properties` containing `sdk.dir=…`, or set
`ANDROID_HOME`. `local.properties` is git-ignored and must stay that way — it holds a path that only
exists on your machine.

## Android

```bash
./gradlew test
./gradlew :mobile:lintRelease :automotive:lintRelease
./gradlew :mobile:assembleRelease :automotive:assembleRelease
```

Lint must report **zero errors**. There is deliberately no baseline file: a new lint error is a
thing to fix or to suppress narrowly at the site, with a comment saying why.

Instrumented tests (Compose UI and the Car App browse tree) need a device or emulator:

```bash
./gradlew :mobile:connectedDebugAndroidTest
```

### Where things live

| Module | Contains |
|---|---|
| `shared` | Everything both apps use: catalog, playlists, settings, speed routing, the stem mixing engine, the media session, and the Compose UI |
| `mobile` | Phone activity, phone GPS speed source, Android Auto manifest wiring |
| `automotive` | AAOS activity and the native vehicle-speed source |
| `projected` | The `CarAppService` that Android Auto talks to |

Rules that decide behaviour are kept in small, Android-free objects — `PlaybackRules`,
`PlaybackQueueRules`, `PlaylistOperations`, `OverlayGroupScheduler` — so they can be unit-tested
directly. Prefer adding logic there over adding it to a controller.

## Windows editor

```bash
dotnet build   desktop_editor/DynamicDriving.slnx -c Release
dotnet test    desktop_editor/DynamicDriving.slnx -c Release
```

NuGet dependencies are pinned by checked-in `packages.lock.json` files. CI restores with
`--locked-mode`, so if you change a package version you must commit the updated lock file:

```bash
dotnet restore desktop_editor/DynamicDriving.slnx --force-evaluate
```

### Producing the portable ZIP

```bash
dotnet build desktop_editor/DynamicDriving.slnx -c Release
```

The Release build automatically publishes exactly one self-contained file at
`artifacts/editor/DynamicDriving.Editor.exe`. If publish emits any companion DLL, JSON, PDB, or
subfolder, the build fails instead of packaging it.

Then check the result really works before shipping it:

```bash
artifacts/editor/DynamicDriving.Editor.exe --self-check shared/src/main/assets/songs/open_road
```

`--self-check` loads the window, opens the song, previews it, edits it, saves a copy, reopens the
copy, and exits non-zero with a reason if any step fails. CI runs exactly this against the unzipped
release artifact.

## The manifest contract

`song.json` is shared by two parsers — Kotlin in the app, C# in the editor — and they must not drift.

- The schema is `contracts/song.schema.json`.
- The corpus is `contracts/fixtures/*.json`, with the agreed parse result in `expected.json`.
- Both suites assert against that same file: `SongManifestContractTest` (Kotlin) and
  `SongManifestContractTests` (C#).

Changing the format means changing the schema, the fixtures, `expected.json`, both parsers, and
[SONG_JSON.md](SONG_JSON.md) together. A change to one parser alone will fail the other's tests,
which is the point.

Unknown JSON properties are preserved by the editor on a round trip. If you add a field, add it to
the schema and to both parsers rather than relying on that.

## What must not be committed

- Signing material of any kind: keystores, `.jks`, passwords, or base64 blobs of them. Release
  signing reads repository secrets in CI and nothing else.
- `local.properties`, IDE folders, or anything else naming a path on your machine.
- Build output: `build/`, `bin/`, `obj/`, `artifacts/`, APKs.
- Private audio. Test audio is generated procedurally at run time, and the only committed audio is
  the CC0 demo.

## Style

Match the file you are editing. Across the codebase:

- Comments explain *why*, and are worth writing where a decision would otherwise look arbitrary —
  strict speed sources, the transactional folder switch, the artwork provider not being exported.
- Public types carry a short doc comment saying what they are for.
- Tests are named as sentences describing the behaviour they pin.
