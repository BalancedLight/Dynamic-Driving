# Dynamic Driving Editor

A Windows song editor for Dynamic Driving: author a `song.json`, place its loop, and hear the speed
rules take effect before the song ever reaches a car.

Ships as one self-contained `DynamicDriving.Editor.exe` inside a portable ZIP. No Python, loose DLL
folder, or separate .NET runtime is required.

## Projects

| Project | Target | What it holds |
|---|---|---|
| `DynamicDriving.Core` | `net10.0-windows` | Manifest model, JSON round-tripping, validation, WAV reading, waveform, scheduling rules, and the NAudio stem mixer |
| `DynamicDriving.Editor` | `net10.0-windows` (WPF) | The three-pane MVVM UI |
| `DynamicDriving.Tests` | `net10.0-windows` | xUnit contract, transport, validation, and generated-audio tests |

Dependencies are `NAudio` and `CommunityToolkit.Mvvm`, both pinned by checked-in
`packages.lock.json` files.

## Using it

**Open folder** points the editor at a folder holding one song: its WAV stems and, if it has one, a
`song.json`. A folder with no manifest is not an error — the editor builds a starting one from the
WAVs it finds, so a new song can be authored from nothing.

The window is three panes:

- **Left — song and stems.** Title, artist, album, cover art, and every stem in the folder. Untick a
  stem to keep the file but leave it out of the saved song. The bar beside each stem is its live
  level while previewing.
- **Middle — waveform and transport.** The transport stem's waveform with the loop region drawn over
  it. Click to seek, drag either loop handle, or type exact millisecond values and press **Apply**.
  Below it: play/pause, stop, play tail, the speed slider with presets, and the validation list.
- **Right — inspector.** Typed fields for everything the manifest can express: song metadata, muffle,
  the selected stem's gain and fades, its base or overlay rule, and its events with typed conditions
  and modifiers.

**Speed simulation** is the point of the middle pane. Drag the slider and stems fade in and out
against their rules, overlays fire and cool down, and the song muffle releases — the same rules the
Android engine runs, so what you hear here is what the car will do.

## Saving

**Save** writes `song.json` into the open folder. **Save a copy** writes to another folder, taking
the stems and cover with it.

Saving is atomic and validated: the manifest is checked and serialized, the result is parsed back to
prove it can be read, and only then is the file swapped into place. A manifest that fails validation
never replaces the one already on disk, and a failed save leaves the previous file untouched.

Properties the editor does not understand are preserved verbatim, so a manifest written by a newer
tool survives a round trip here rather than being silently stripped.

## Checking a build

```bash
DynamicDriving.Editor.exe --self-check path\to\song\folder
```

Loads the window, opens the song, previews it, edits it, saves a copy, reopens the copy, and exits
non-zero with a reason if any step fails. This is what CI runs against the published ZIP.

## Building

```bash
dotnet test    DynamicDriving.slnx -c Release
dotnet build   DynamicDriving.slnx -c Release
```

A normal Release build automatically publishes the single executable to
`../artifacts/editor/DynamicDriving.Editor.exe`. Debug builds keep the ordinary fast build layout.

## Limitations

- Only 16-bit PCM WAV is supported, mono or stereo. This matches the Android engine exactly rather
  than letting a file that cannot play in the car look fine in the editor.
- All stems in a song must share a sample rate, channel count, bit depth, and length. The validator
  reports any that do not.
- Reverb modifiers are edited and saved faithfully but are not rendered in the desktop preview; gain,
  fades, speed rules, overlay scheduling, and muffling all are.
- The preview needs an audio output device. Without one the editor still opens, edits, and saves —
  the status bar says the device was unavailable.

The manifest format is documented in [SONG_JSON.md](../SONG_JSON.md) and specified by
[contracts/song.schema.json](../contracts/song.schema.json).
