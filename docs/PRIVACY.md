# Privacy

Dynamic Driving has no accounts, no analytics, no crash reporting, no advertising, and no network
calls of any kind. Everything the app reads stays on the device it runs on.

This document describes exactly what is read, why, and where it goes.

## The strongest guarantee first

Neither APK declares `android.permission.INTERNET`. Without it, Android will not let the app open a
network connection at all — not to a server we run, not to anyone else's. You can confirm this on the
file you downloaded rather than taking it on trust:

```bash
aapt2 dump permissions DynamicDriving-mobile-v1.0.0.apk
```

You will see `ACCESS_NETWORK_STATE` in that list. It arrives transitively from the AndroidX media and
car libraries, only allows *reading* whether a network exists, and is inert without `INTERNET`.

## Location and speed

Adaptive playback needs one number: how fast you are moving. Where that number comes from depends on
the source you pick in **Settings → Speed source**.

| Source | Where the number comes from | Permission needed |
|---|---|---|
| Automatic | Vehicle speed in a car, otherwise phone GPS | Location, only when GPS is used |
| Car | The vehicle's own speed sensor | `CAR_SPEED` (AAOS) or the projected car permission |
| Phone GPS | This phone's location provider | Location |
| Manual | A number you set with a slider | None |

What happens to a speed reading:

- It is used to decide which stems are audible right now, then discarded.
- It is **never written to storage**. Nothing in the app's saved settings records a speed, a
  position, a route, or a timestamp of where you were.
- It is **never transmitted**. The app has no networking code and requests no internet permission.

### Why there is no background-location permission

GPS is read only through the media foreground service — the same service that is playing your music,
with a visible notification. When playback stops, the location listener stops. Because the app never
needs a location while it has nothing running in the foreground, it never requests
`ACCESS_BACKGROUND_LOCATION`, and the manifest does not contain it.

If you choose **Manual** or **Car**, the app does not start the location provider at all.

## Your music

- You choose a folder. The app reads `song.json` manifests and the WAV files they reference from
  inside that folder, using the access grant Android gives it for that folder alone.
- Nothing is copied out of that folder. Nothing is uploaded.
- Cover art is downscaled into a small cached JPEG inside the app's own cache directory so car
  screens can display it. That cache is pruned to the songs currently in your library and is cleared
  with the app's data.

## What other apps can see

Dynamic Driving publishes a standard Android media session so the notification, the lock screen,
Android Auto, and Android Automotive OS can show what is playing and offer transport controls. The
session carries the title, artist, album, and cover art **declared in your song's manifest**, plus a
stable media ID.

Two consequences worth being explicit about:

- Any app you have installed that scrobbles from a media session — a Last.fm client, for example —
  can read that metadata. Dynamic Driving contains no scrobbling code, no service credentials, and no
  network client of its own; it simply publishes accurate metadata, and what you do with it is your
  choice.
- If a manifest declares no artist or album, the app publishes **nothing** for that field rather than
  a placeholder. "Unknown artist" appears only inside the app's own screens, so a scrobbler never
  records an invented artist against your listening history.

The cover-art provider that serves images to car screens is not exported. The media service grants
read access to individual artwork URIs only to a browser that has actually connected to the session,
so no other app on the device can enumerate your cover art.

## Diagnostics

Debug builds show stem gains, mixer timings, and underrun counters on screen for development. Release
builds do not construct that data at all — there is no hidden switch to turn it on.

## The Windows editor

`DynamicDriving.Editor` reads and writes only the song folder you open. It makes no network calls and
collects nothing. It writes `song.json` atomically: an invalid document is rejected before anything
on disk is touched, so a failed save never destroys the manifest you already had.

## Questions

If something here does not match what you observe, that is a bug worth reporting. The source is the
final word: the speed plumbing lives in
`shared/src/main/java/com/BalancedLight/dynamicdriving/shared/speed/`, and the permissions each build
declares are in `mobile/src/main/AndroidManifest.xml` and `automotive/src/main/AndroidManifest.xml`.
