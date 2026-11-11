# andWebCam

A headless-first LAN webcam for Android. The phone captures its camera,
serves it over HTTPS as an MJPEG stream with a control web page, and
advertises itself over mDNS/DNS-SD so the desktop can find it and resolve
`andwebcam.local` without any manual configuration.

Designed for a phone that you don't have physical access to: it runs as a
foreground service, starts at boot, and every setting is changeable from the
web UI.

## What runs where

```
  phone                                        desktop
  -----                                        -------
  camera2  --JPEG-->  StreamHub  -->  HTTPS server (8443)
                                          |  /            control page
                                          |  /video       MJPEG stream
                                          |  /api/...     status + settings
                                          |  /cert        server certificate
  mDNS responder  --announce-->  224.0.0.251:5353
                                          |
                              avahi-resolve andwebcam.local -> <ip>
```

## Building

```
scripts/make-cert.sh          # regenerate the bundled TLS cert (only if needed)
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant net.andwebcam android.permission.CAMERA
```

The camera permission has to be granted once. If the phone is unattended you
can grant it over adb exactly as above; otherwise accept the prompts on first
launch (camera, notifications on Android 13+, and a battery-optimisation
exemption so the stream survives Doze).

### Restarting the phone from the web UI

Android gives no way for an ordinary app to reboot the device. andWebCam uses
device owner, which you set once over adb (the phone must have no accounts):

```
adb shell dpm set-device-owner net.andwebcam/.AdminReceiver
```

The control page's **Restart phone** button then calls
`DevicePolicyManager.reboot()`. Without device owner the button reports that
error instead. Removing the app again requires clearing the owner first:

```
adb shell dpm remove-active-admin net.andwebcam/.AdminReceiver
```

## Using it

Open `https://andwebcam.local:8443/` (or the IP shown on the phone). The page
shows a narrow status banner, the live stream (as large as the window allows)
and, when logged in, all the controls.

The **Settings** button in the header (and the **»** button at the right of the
panel's action row) shows and hides the settings pane, so the video can take the
full width when you are only watching. The choice is remembered in that browser.

While the stream is connecting — after a reload, or while it reconnects after a
drop — the video area shows *Loading video...* instead of a broken image, and
the picture appears as soon as the first frame has arrived.

The header also has four scaling buttons for the video:

| Button | Meaning                                                  |
|--------|----------------------------------------------------------|
| `Fit`  | fit the whole frame into the viewing area (default)      |
| `W`    | fit the width, may crop vertically                        |
| `H`    | fit the height, may crop horizontally                     |
| `1:1`  | actual pixels, no scaling                                 |

When the picture is larger than the viewing area it is clipped to it and can be
dragged with the mouse, or scrolled with the wheel, to pan; at an edge the wheel
scrolls the page again. Switching mode re-centres it. The mode is remembered in
the browser too.

Camera settings (resolution, camera, orientation, frame rate, quality, zoom) do
not take effect instantly. A small **Applying...** spinner appears over the video
as soon as such a control is used, and stays until the phone acknowledges the
change, so it is clear something is happening.

### Streaming, idle and power

* **Start / Stop streaming** buttons turn the stream on and off. While stopped,
  `/video` answers 503 and viewers are disconnected.
* **Start streaming when the service starts** (default on) decides whether
  streaming is enabled after a boot or service restart. It is a stored
  preference; toggling it does not stop a stream that is already running.
* The camera itself is **only open while somebody is watching**. When the last
  viewer leaves, the camera is closed after a few seconds, so an unused phone
  does not wear out its camera or battery. The next viewer opens it again.
* The phone screen shows whether the service is running, streaming or idle.

### Authentication

* On first run a random admin password is generated and **shown on the phone
  screen** together with the address. Log in as `admin` with that password.
* Once you change the password in the web UI it is stored as a PBKDF2 hash and
  the phone **stops displaying it**.
* Anonymous **view-only** access (stream + status, no controls) is available as
  an option and is **off by default**.
* Both a session cookie (the web UI) and HTTP Basic (ffmpeg/curl) are accepted.

### API

| Method | Path             | Auth            | Purpose                          |
|--------|------------------|-----------------|----------------------------------|
| GET    | `/`              | view            | control page                     |
| GET    | `/video`         | view            | MJPEG stream                     |
| GET    | `/api/status`    | view            | JSON status + capabilities       |
| POST   | `/api/settings`  | admin           | change any setting               |
| POST   | `/api/password`  | admin           | change the admin password        |
| POST   | `/api/login`     | none            | get a session cookie             |
| POST   | `/api/logout`    | none            | drop the session                 |
| POST   | `/api/reboot`    | admin           | restart the phone (device owner) |
| GET    | `/cert`          | none            | download the server certificate  |

Settings that can be changed via `/api/settings`:

`facing` (back/front), `resolution` (WxH), `fps`, `quality` (JPEG 10-100),
`torch` (bool), `zoom` (1..max), `orientation` (0/90/180/270), `anonView`
(bool), `autoStartStreaming` (bool), `streaming` (bool), `startOnBoot` (bool),
`serviceName`, `port`.

`streaming` takes effect immediately (it is not a stored preference; use
`autoStartStreaming` for that). Changing `resolution`, `facing`, `serviceName`
or `port` rebuilds the capture session or restarts the server.

## Consuming it on the desktop

Trust the certificate once:

```
curl -sk https://andwebcam.local:8443/cert -o andwebcam.pem
```

Then, for example, into a virtual webcam:

```
sudo modprobe v4l2loopback video_nr=10 card_label=andWebCam exclusive_caps=1
ffmpeg -i https://andwebcam.local:8443/video -c:v rawvideo -pix_fmt yuv420p -f v4l2 /dev/video10
```

(`-ca_file andwebcam.pem` in place of `-k` once you trust the cert.)

A long-lived stream is safer with ffmpeg's reconnect options, which survive a
Wi-Fi blip instead of exiting:

```
ffmpeg -reconnect 1 -reconnect_streamed 1 -reconnect_delay_max 2 \
       -i "https://admin:PASSWORD@andwebcam.local:8443/video" ...
```

The web page reconnects the video element by itself if the stream is cut.

## Notes / quirks

* The bundled TLS certificate is self-signed with SAN `andwebcam.local`
  (`scripts/make-cert.sh`). Because it ships in the APK its private key is not
  secret; it exists to make the stream encrypted and the name verifiable on a
  trusted LAN, not to authenticate the device cryptographically.
* Orientation is applied as the JPEG's EXIF orientation, which browsers and
  ffmpeg honour without any transcoding cost on the phone.
* Some devices violate the camera2 spec by reporting AE fps ranges in plain
  fps instead of fps×1000; the code detects the scale automatically.
* The capture session deliberately targets **only** the JPEG ImageReader. Adding
  a preview `SurfaceTexture` without a GL consumer starves the Exynos preview
  pipe and crashes `cameraserver` (`ExynosCameraFrame::searchEntityByPipeId`
  null-deref) after a few seconds. The camera is also auto-reopened a few
  seconds after any error, so a HAL hiccup self-heals on an unattended phone.
* No AndroidX or third-party dependencies are used, to keep the build small.
* The service holds a high-performance Wi-Fi lock and a partial wake lock, so
  the stream keeps running at full rate with the screen off.
* A viewer connection can be reset by the network; the hub drops the client
  after three failed writes, the camera closes a few seconds later, and the web
  page or ffmpeg reconnect wakes it up again.
