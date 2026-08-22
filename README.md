# Dynamic Driving

Music that follows the road. Dynamic Driving plays layered songs whose stems fade in and out with how
fast you are actually moving: standing still the mix is quiet and muffled, and as you speed up the
layers arrive.

It runs on the phone, in Android Auto, and on Android Automotive OS, and ships with a Windows editor
for authoring the songs.

| | |
|---|---|
| **Phone / Android Auto** | `mobile` — Material 3 Compose app, media notification, Android Auto templated media |
| **Android Automotive OS** | `automotive` — the same app built against the vehicle's own speed sensor |
| **Windows editor** | `desktop_editor` — .NET 10 WPF song editor, published as a portable ZIP |
| **Bundled demo** | `Open Road`, an original CC0 song, so there is something to play immediately |

Everything stays on the device: no accounts, no analytics, no network calls. Neither APK declares the
`INTERNET` permission, so the app cannot make one. See [docs/PRIVACY.md](docs/PRIVACY.md).

## How adaptive playback works

A song is a folder holding a `song.json` manifest and one 16-bit PCM WAV per stem.

- **Base stems** are speed bands. A stem with `minMph: 30` is silent in town and arrives on the
  motorway. Bands have hysteresis, so a speed hovering on a threshold does not chatter the layer.
- **Overlay stems** are timed one-shots grouped by `groupId`. One overlay per group plays at a time,
  for a set duration, then waits out a randomised cooldown.
- **Events** apply gain and reverb changes while a speed condition holds.
- **Song muffle** rolls a low-pass filter across everything while you are barely moving, and releases
  as you pick up speed.
- The **loop region** is what repeats. Audio after it is the tail, played once on the way out to the
  next song.

The format is documented in [SONG_JSON.md](SONG_JSON.md) and specified by
[contracts/song.schema.json](contracts/song.schema.json).

## The app

Four destinations:

- **Now Playing** — artwork, title, artist, album, previous/play/next, the active playlist, the
  playback mode, and the current speed with the source it came from.
- **Library** — the selected folder, change and refresh, scan progress, readable diagnostics, and a
  card per song.
- **Playlists** — create, rename, delete, drag-reorder playlists and their songs, add and remove.
- **Settings** — speed source, manual speed, playback mode, loops before advancing, permissions, and
  an About section.

### Speed sources

| Selection | Behaviour |
|---|---|
| Automatic (default) | AAOS prefers native vehicle speed; Android Auto prefers projected car speed and falls back to phone GPS; an unconnected phone uses GPS |
| Car | Only the vehicle's speed. Never falls back |
| Phone GPS | Only this phone's GPS. Never falls back |
| Manual | A speed you set with a slider |

An explicit selection is strict on purpose: if the source it names cannot produce a reading, the app
shows 0 mph with an explanation and a one-tap route back to Automatic, rather than quietly
substituting a different source.

### Playback modes

`Repeat song` (default), `Play in order`, and `Shuffle`, with a 1–8 setting for how many loops elapse
before moving on. Next and previous always step within the active collection and wrap.

## Building

Requirements: **JDK 21**, the **Android SDK** with platform 36, and the **.NET 10 SDK** for the
editor.

```bash
./gradlew test lintRelease :mobile:assembleRelease :automotive:assembleRelease
```

```bash
dotnet test desktop_editor/DynamicDriving.slnx -c Release
```

Full instructions, including how to produce the portable editor ZIP, are in
[CONTRIBUTING.md](CONTRIBUTING.md).

## Releases

Each tagged release publishes four files:

| Artifact | What it is |
|---|---|
| `DynamicDriving-mobile-v1.0.0.apk` | Phone and Android Auto build, signed |
| `DynamicDriving-automotive-v1.0.0.apk` | Android Automotive OS build, signed |
| `DynamicDriving-Editor-v1.0.0-win-x64.zip` | Portable Windows editor, self-contained |
| `SHA256SUMS.txt` | Checksums for the three files above |

### Verifying what you downloaded

Check the hashes:

```bash
sha256sum --check --ignore-missing SHA256SUMS.txt
```

On Windows PowerShell:

```powershell
Get-FileHash .\DynamicDriving-mobile-v1.0.0.apk -Algorithm SHA256
```

Check that an APK really is signed, and see the signing certificate:

```bash
apksigner verify --print-certs --verbose DynamicDriving-mobile-v1.0.0.apk
```

The editor ZIP is **unsigned** for v1.0.0, so Windows SmartScreen will warn the first time you run
it. Verify the checksum before trusting it. Authenticode signing is future scope. Because the editor
is a self-contained, unsigned .NET application, some antivirus products flag it heuristically; the
build is reproducible from source with the command in [CONTRIBUTING.md](CONTRIBUTING.md) if you would
rather build it yourself.

## Running the editor

The editor ZIP contains one file: `DynamicDriving.Editor.exe`. Unzip it anywhere and run it. Nothing
to install: no Python, loose dependency folders, or separate .NET runtime.

Three panes: the song and its stems on the left, the waveform with loop handles and the transport in
the middle, and a typed inspector on the right. Drag the speed slider to hear the stem rules take
effect, and watch the live level beside each stem.

The editor can check itself against a song folder, which is what CI runs against the published ZIP:

```bash
DynamicDriving.Editor.exe --self-check path\to\song\folder
```

## Repository layout

```
mobile/          Phone + Android Auto application
automotive/      Android Automotive OS application
shared/          Domain, playback engine, media session, and the Compose UI both apps use
projected/       Android Auto templated-media entry point
contracts/       song.json schema and the fixtures both parsers are tested against
desktop_editor/  .NET 10 WPF editor (Core, Editor, Tests)
docs/            Privacy and screenshots
```

## Licence

[MIT](LICENSE). The bundled demo song is CC0 — see
[its notice](shared/src/main/assets/songs/open_road/NOTICE.md).
