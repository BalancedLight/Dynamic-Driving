# Shared media contracts

This folder is the single source of truth for the `song.json` manifest format that both
implementations must agree on:

- **Android / Kotlin** — `shared/src/main/java/.../catalog/SongCatalogParser.kt`
- **Windows editor / C#** — `desktop_editor/DynamicDriving.Core/Manifests/SongManifestSerializer.cs`

## Files

| File | Purpose |
|---|---|
| `song.schema.json` | JSON Schema (2020-12) for a `song.json` manifest. |
| `fixtures/*.json` | The shared corpus. Generic content only — no machine-specific paths, no private audio. |
| `fixtures/expected.json` | The canonical projection of every fixture. |

## How parity is enforced

Both test suites read the same fixtures and assert against the same `expected.json`:

- Kotlin: `shared/src/test/java/.../catalog/SongManifestContractTest.kt`
- C#: `desktop_editor/DynamicDriving.Tests/SongManifestContractTests.cs`

If one parser changes behaviour without the other following, the two suites disagree with
`expected.json` and the build fails. `unknown-properties.json` additionally pins the editor's
round-trip guarantee: properties the editor does not understand survive a load/save cycle
untouched, so a manifest written by a newer tool is never silently stripped.

Fixture audio is never referenced on disk. The fixtures name `assetPath` values such as
`main.wav`; each test supplies its own resolver, so the corpus stays free of binary files.
