# song.json Authoring Guide

This document describes the current `song.json` format used by Dynamic Driving.

A machine-readable JSON Schema lives at [`contracts/song.schema.json`](contracts/song.schema.json),
and the shared fixture corpus that both the Kotlin and C# parsers are tested against lives in
[`contracts/fixtures`](contracts/fixtures). If this document and the schema ever disagree, the
schema and its fixtures are the contract.

## Overview

Each song lives in its own folder and is usually described by a `song.json` file next to the audio stems it references.

Minimum structure:

```json
{
  "songId": "example_song",
  "displayName": "Example Song",
  "artist": "Example Artist",
  "album": "Example Album",
  "transportStemId": "main",
  "loopRegion": {
    "startMs": 1000,
    "endMs": 9000
  },
  "stems": [
    {
      "stemId": "main",
      "displayName": "Main",
      "assetPath": "main.wav",
      "rule": { "type": "base" }
    }
  ]
}
```

## Top-Level Fields

`songId`
- Required string.
- Unique internal identifier for the song.

`displayName`
- Required string.
- Human-readable song title shown in the UI and published as the MediaMetadata title.

`artist`
- Optional string.
- Published as the MediaMetadata artist to the notification, the lock screen, Android Auto, AAOS,
  and any external scrobbler listening to the session.
- Omit it when you do not know it. Dynamic Driving shows "Unknown artist" in its own UI but publishes
  nothing, so a scrobbler never records an invented artist.

`album`
- Optional string.
- Published as the MediaMetadata album title. Same rule: omit rather than invent.

`transportStemId`
- Required string.
- Must match one of the `stemId` values in `stems`.
- Used by the transport player timeline.

`loopRegion`
- Required object.
- Defines the main loop section.

`muffle`
- Optional object.
- Applies song-level muffling that fades out as speed increases.

`stems`
- Required array.
- Must contain at least one stem.

## loopRegion

```json
"loopRegion": {
  "startMs": 4795,
  "endMs": 76941,
  "playTailOverLoop": true,
  "loopStartsSong": false
}
```

`startMs`
- Required integer.
- Loop start position in milliseconds.

`endMs`
- Required integer.
- Loop end position in milliseconds.
- Must be greater than `startMs`.

`playTailOverLoop`
- Optional boolean. Default: `false`.
- When `true`, audio after `endMs` is layered on top of the looping section during normal looping playback.
- This lets songs keep a trailing tail while the musical loop restarts.

`loopStartsSong`
- Optional boolean. Default: `true`.
- When `true`, playback starts at the loop start.
- When `false`, the first playthrough starts from the beginning of the file and then falls into the loop after reaching `endMs`.

## muffle

```json
"muffle": {
  "releaseMph": 1.0,
  "wetMix": 0.85,
  "cutoffHz": 300.0,
  "fadeMs": 1200
}
```

`releaseMph`
- Optional number. Default: `1.0`.
- Speed where the song-level muffle has fully released.

`wetMix`
- Optional number from `0.0` to `1.0`. Default: `0.85`.
- How much filtered signal is blended in while muffling is active.

`cutoffHz`
- Optional number from `20.0` to `18000.0`. Default: `300.0`.
- Low-pass cutoff used at maximum muffling.
- Lower values sound more muffled.

`fadeMs`
- Optional integer. Default: `1200`.
- Parsed today and reserved for fade timing; keep setting it for forward compatibility.

## stems

Each entry in `stems` describes one audio layer.

```json
{
  "stemId": "guitar",
  "displayName": "Guitar",
  "assetPath": "guitar.wav",
  "playTailOverLoop": false,
  "gain": 1.0,
  "fadeInMs": 1500,
  "fadeOutMs": 1500,
  "rule": { "type": "base", "minMph": 20.0 },
  "events": []
}
```

`stemId`
- Required string.
- Unique stem identifier within the song.

`displayName`
- Required string.
- Human-readable name.

`assetPath`
- Required string.
- Relative path from the `song.json` file to a 16-bit PCM WAV file.

`playTailOverLoop`
- Optional boolean. Default: `true`.
- Only matters when `loopRegion.playTailOverLoop` is `true`.
- When `true`, this stem continues contributing its post-loop tail over the restarted loop.
- When `false`, this stem stops at `endMs` and does not layer tail audio over the loop.
- Use this to keep some stems clean and tightly looped while others ring out.

`gain`
- Optional number. Default: `1.0`.
- Base output gain for the stem.

`fadeInMs`
- Optional integer. Default: `1500`.
- Fade time when the stem becomes active.

`fadeOutMs`
- Optional integer. Default: `1500`.
- Fade time when the stem becomes inactive.

`rule`
- Required object.
- Determines when the stem may play.

`events`
- Optional array. Default: `[]`.
- Event-driven modifiers applied to this stem.

## Stem Rules

### Base Rule

Base stems are the main always-available speed layers.

```json
"rule": {
  "type": "base",
  "minMph": 5.0,
  "maxMphExclusive": 45.0
}
```

`type`
- Must be `"base"`.

`minMph`
- Optional number.
- Minimum speed where the stem becomes eligible.

`maxMphExclusive`
- Optional number.
- Upper bound where the stem stops being eligible.

### Overlay Rule

Overlay stems are one-shot or timed layers grouped into cooldown-managed overlay groups.

```json
"rule": {
  "type": "overlay",
  "minMph": 30.0,
  "maxMphExclusive": 70.0,
  "groupId": "lead",
  "groupName": "Lead",
  "durationMs": 20000,
  "cooldownMinMs": 20000,
  "cooldownMaxMs": 40000,
  "weight": 2
}
```

`type`
- Must be `"overlay"`.

`minMph`
- Required number.

`maxMphExclusive`
- Optional number.

`groupId`
- Required string.
- Only one overlay in the same group plays at a time.

`groupName`
- Optional string. Defaults to `groupId`.

`durationMs`
- Optional integer. Default: `20000`.
- How long the overlay can stay active.

`cooldownMinMs`
- Optional integer. Default: `20000`.

`cooldownMaxMs`
- Optional integer. Default: `40000`.

`weight`
- Optional integer. Default: `1`.
- Higher weights make the overlay more likely to be chosen.

## Events

DynamicDriving currently supports stem-local events. Each event has:

- `eventId`: optional string, defaults to `event_#`
- `displayName`: optional string shown in debug UI
- `condition`: required object
- `modifiers`: required non-empty array

Example:

```json
{
  "eventId": "drive_fx",
  "displayName": "Drive FX",
  "condition": {
    "all": [
      { "metric": "mph", "operator": ">", "value": 5.0 },
      { "metric": "mph", "operator": "<", "value": 35.0 }
    ]
  },
  "modifiers": [
    { "type": "gainMultiplier", "multiplier": 0.5, "fadeMs": 1200 },
    { "type": "reverb", "wetMix": 0.4, "feedback": 0.6, "damping": 0.3, "delayMs": 180.0, "fadeMs": 1500 }
  ]
}
```

## Event Conditions

### `all`

All child conditions must be true.

```json
"condition": {
  "all": [
    { "metric": "mph", "operator": ">=", "value": 15.0 },
    { "metric": "mph", "operator": "<", "value": 45.0 }
  ]
}
```

### `any`

At least one child condition must be true.

```json
"condition": {
  "any": [
    { "metric": "mph", "operator": "<", "value": 5.0 },
    { "metric": "mph", "operator": ">", "value": 60.0 }
  ]
}
```

### MPH comparison

This is the current leaf condition type.

```json
"condition": {
  "metric": "mph",
  "operator": ">=",
  "value": 30.0
}
```

Supported operators:

- `gt` or `>`
- `gte` or `>=`
- `lt` or `<`
- `lte` or `<=`
- `eq` or `==`
- `neq` or `!=`

Current supported metric:

- `mph`

## Event Modifiers

### gainMultiplier

```json
{ "type": "gainMultiplier", "multiplier": 0.8, "fadeMs": 1500 }
```

`multiplier`
- Optional non-negative number. Default: `1.0`.
- `1.0` is unchanged volume, values below `1.0` attenuate, and values above `1.0` amplify.
- Amplification can clip when the combined stem mix exceeds full scale.

`fadeMs`
- Optional integer. Default: `1500`.

### reverb

```json
{
  "type": "reverb",
  "wetMix": 0.35,
  "feedback": 0.55,
  "damping": 0.35,
  "delayMs": 140.0,
  "fadeMs": 1500
}
```

`wetMix`
- Optional number from `0.0` to `1.0`. Default: `0.35`.

`feedback`
- Optional number from `0.0` to `0.98`. Default: `0.55`.

`damping`
- Optional number from `0.0` to `0.98`. Default: `0.35`.

`delayMs`
- Optional number from `20.0` to `750.0`. Default: `140.0`.

`fadeMs`
- Optional integer. Default: `1500`.

## Validation Rules

- All stems must use the same sample rate.
- All stems must use the same channel count.
- All stems must use the same bit depth.
- All stems must use the same duration.
- Only 16-bit PCM WAV files are supported.
- `transportStemId` must match a real stem.
- `loopRegion.endMs` must be after `loopRegion.startMs`.
- `loopRegion.endMs` must not exceed the audio length.
- Overlay events require at least one modifier.

## Metadata and external scrobblers

Dynamic Driving publishes `displayName`, `artist`, `album`, cover art, and a stable media ID
(the `songId`) through Media3. Any app that already scrobbles from a MediaSession will therefore see
real values.

Dynamic Driving has no Last.fm credentials, no network API, and no built-in scrobbling of its own.
Publishing accurate metadata is the whole of the integration, which is why the parser deliberately
treats a missing or blank `artist`/`album` as absent instead of substituting a placeholder.

## Practical Notes

- If a song has no `song.json`, it is simply unavailable to the manifest-driven system until the file is added.
- Start with one `base` stem and add more stems only after the loop points and transport stem behave correctly.
- If a loop sounds smeared with `playTailOverLoop`, disable stem-level `playTailOverLoop` on rhythmic stems first.
- Use lower `cutoffHz` values in `muffle` for a clearly audible muffled effect.
