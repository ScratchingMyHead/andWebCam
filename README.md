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
can grant it over adb exactly as above; otherwise accept the prompt on first
launch.

## Using it

Open `https://andwebcam.local:8443/` (or the IP shown on the phone). The page
shows the live stream and, when logged in, all the controls.

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
| GET    | `/cert`          | none            | download the server certificate  |

Settings that can be changed via `/api/settings`:

`facing` (back/front), `resolution` (WxH), `fps`, `quality` (JPEG 10-100),
`torch` (bool), `zoom` (1..max), `orientation` (0/90/180/270), `anonView`
(bool), `startOnBoot` (bool), `serviceName`, `port`.

Changing `resolution`, `facing`, `serviceName` or `port` rebuilds the capture
session or restarts the server.

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

## Notes / quirks

* The bundled TLS certificate is self-signed with SAN `andwebcam.local`
  (`scripts/make-cert.sh`). Because it ships in the APK its private key is not
  secret; it exists to make the stream encrypted and the name verifiable on a
  trusted LAN, not to authenticate the device cryptographically.
* Orientation is applied as the JPEG's EXIF orientation, which browsers and
  ffmpeg honour without any transcoding cost on the phone.
* This particular device (Samsung SM-G390Y) violates the camera2 spec by
  reporting AE fps ranges in plain fps instead of fps×1000; the code detects
  the scale automatically.
* No AndroidX or third-party dependencies are used, to keep the build small.
