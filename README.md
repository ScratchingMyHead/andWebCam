# andWebCam

A headless-first webcam for Android. It turns a phone into an HTTPS MJPEG
camera with a small web UI, on-device recording and optional motion detection.
It runs as a foreground service, advertises itself over mDNS, can be reached
over the internet with an optional public certificate, and is configured
entirely from a browser.

## Features

- **Live stream** — MJPEG over HTTPS at `/video`, with a minimal public page
  when the admin UI is on a separate path. The public page has pause, snapshot
  and Fit / Width / Height / 1:1 controls in a bar that hides when idle, plus
  an optional Sound button for live microphone audio.
- **Live sound** — opt-in 16 kHz mono audio at `/audio`, played by the browser's
  AudioContext. The microphone is open only while someone is listening.
- **Camera controls** — front or back camera, resolution, frame rate, JPEG
  quality, zoom, orientation and torch, all adjustable while streaming.
- **Recording** — MP4 files on the phone with a 10-second pre-roll, optional
  AAC sound, a storage cap, in-page playback and downloads with byte-range
  support.
- **Motion activation** — cell-grid detector or OpenCV object tracking with
  polygon zones, live object boxes and automatic recordings.
- **Networking** — mDNS/DNS-SD discovery (`<name>.local`), optional anonymous
  view-only access, and optional Let's Encrypt certificates with DuckDNS
  address updates.
- **Public proxy** — an outbound HTTPS connection to your own relay, so the
  full web UI is reachable on a public subdomain even behind CGNAT.
- **Operations** — web-based APK updates, remote log access, an on-device log
  file and crash traces, and service / phone restart controls.

## Requirements

- An arm64-v8a Android phone (Android 9 / API 28 or newer), reachable from
  wherever you plan to watch.
- Camera permission. Microphone permission for live sound (optional).
  Notifications permission on Android 13+ for the
  foreground service. Battery-optimisation exemption is recommended so the
  camera survives with the screen off.
- To build from source: JDK 17, Android SDK 34, release signing properties in
  `local.properties`, and the OpenCV Android AAR in `mavenLocal()` (or Maven
  Central).

## Install

Release APKs are published at
[github.com/ScratchingMyHead/andWebCam/releases](https://github.com/ScratchingMyHead/andWebCam/releases).

From source:

```
./gradlew assembleRelease
```

The release APK is written to `app/build/outputs/apk/release/`.

## Quick start

- After installation, connect to the address displayed on the phone.
- Log in using the password displayed on the phone and set a new admin password.

## Documentation

[DOCUMENTATION.md](DOCUMENTATION.md)
