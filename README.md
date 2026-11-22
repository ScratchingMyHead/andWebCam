# andWebCam

A headless-first LAN webcam for Android. It turns a phone into an HTTPS MJPEG
camera with a small web UI, on-device recording and optional motion detection.
It runs as a foreground service, advertises itself over mDNS, and is configured
entirely from a browser.

## Features

- **Live stream** — MJPEG over HTTPS at `/video`, with a minimal public page
  when the admin UI is on a separate path. The public page has pause, snapshot
  and Fit / Width / Height / 1:1 controls in a bar that hides when idle.
- **Camera controls** — front or back camera, resolution, frame rate, JPEG
  quality, zoom, orientation and torch, all adjustable while streaming.
- **Recording** — MP4 files on the phone with a 10-second pre-roll, a storage
  cap, in-page playback and downloads with byte-range support.
- **Motion activation** — cell-grid detector or OpenCV object tracking with
  polygon zones, live object boxes and automatic recordings.
- **Networking** — mDNS/DNS-SD discovery (`<name>.local`), optional anonymous
  view-only access, and optional Let's Encrypt certificates with DuckDNS
  address updates.
- **Operations** — web-based APK updates, remote log access, an on-device log
  file and crash traces, and service / phone restart controls.

## Requirements

- An arm64-v8a Android phone (Android 9 / API 28 or newer) on the same Wi-Fi
  as the viewer.
- Camera permission. Notifications permission on Android 13+ for the
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

1. Open `https://andwebcam.local:8443/` (or the address shown on the phone).
2. On first run the admin password is generated and displayed on the phone
   screen. Log in as `admin` with it. Once changed in the web UI it is stored
   as a hash and is no longer displayed.
3. The gear, record and motion buttons open the settings, recording and motion
   panes. Most camera settings apply immediately; service settings have an
   **Apply service settings** button.

A blank **Admin page path** puts the admin UI at `/` and disables the public
viewer. By default the admin UI lives at a random path and `/` shows the bare
camera.

## Documentation

[DOCUMENTATION.md](DOCUMENTATION.md) covers every setting, motion zones and
tracking, recording, certificates, storage, updates and the HTTP API.

## Consuming the stream

```
curl -sk https://andwebcam.local:8443/cert -o andwebcam.pem
ffmpeg -ca_file andwebcam.pem -tls_verify 1 \
       -reconnect 1 -reconnect_streamed 1 -reconnect_delay_max 2 \
       -i https://admin:PASSWORD@andwebcam.local:8443/video \
       -c:v rawvideo -pix_fmt yuv420p -f v4l2 /dev/video10
```

The reconnect and certificate options go before `-i`.

## Updating and logs

Upload an APK from the bottom of the settings pane, or:

```
curl -k -u admin:PASSWORD --data-binary @andWebCam.apk \
     https://andwebcam.local:8443/api/update
```

The app restarts itself after installing. Logs can be read with
`/api/log?lines=500` or downloaded from the settings pane.
