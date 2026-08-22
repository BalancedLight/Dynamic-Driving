# Open Road — bundled demo song

`Open Road` is an original demo written for Dynamic Driving so the app has something to play the
moment it is installed.

| Field | Value |
|---|---|
| Title | Open Road |
| Artist | Dynamic Driving Demo |
| Album | Getting Started |
| Length | 14.4 s (9.6 s loop + a 4.8 s tail) |
| Format | 44.1 kHz, mono, 16-bit PCM WAV |

## Contents

| File | Role |
|---|---|
| `pad.wav` | Always-on chord bed. Also the transport stem. |
| `pulse.wav` | Bass pulse; fades in from 8 mph. |
| `drive.wav` | Sixteenth-note arpeggio; fades in from 30 mph. |
| `sparkle.wav` | Bell overlay in the `highlights` group; eligible from 20 mph. |
| `cover.png` | Cover art, 512×512 PNG. |
| `song.json` | The manifest that ties them together. |

## Licence

Every file in this folder — the four stems, the cover art, and the manifest — was generated
specifically for this repository and contains no third-party material: no samples, no loops, no
stock artwork, no fonts.

They are dedicated to the public domain under
[CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/).

You may use, modify, and redistribute them for any purpose, with or without attribution. This is a
deliberately separate and more permissive grant than the MIT licence that covers the source code, so
the demo can be reused freely as a starting point for your own songs.

## Using it as a template

Copy this folder, replace the WAVs with your own, and open it in the Dynamic Driving editor
(`desktop_editor`). The manifest format is documented in [SONG_JSON.md](../../../../../../SONG_JSON.md)
and specified in [contracts/song.schema.json](../../../../../../contracts/song.schema.json).
