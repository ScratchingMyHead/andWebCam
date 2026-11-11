# andWebCam

A headless-first LAN webcam for Android. The phone captures its camera,
serves it over HTTPS as an MJPEG stream with a control web page, and
advertises itself over mDNS/DNS-SD so the desktop can find it and resolve
`andwebcam.local` without any manual configuration.

Designed for a phone that you don't have physical access to: it runs as a
foreground service, starts at boot, and is configured via the web UI.

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

## Installing

Copy the APK to the phone and tap it (or open it from a file manager). Android
asks once for permission to install apps from that source; allow it, install,
then open **andWebCam**.

On first launch the app requests everything it needs, as normal system dialogs:

* **Camera** — required; the stream cannot start without it.
* **Notifications** (Android 13+) — the service runs as a foreground service and
  needs a visible notification.
* **Ignore battery optimisation** — so the stream keeps running with the screen
  off instead of being dozed.

Accept all three. Everything else — camera, resolution, frame rate, quality,
zoom, orientation, torch, port, service name, automatic start — is configured
from the web page at the address shown on the phone screen. No PC, cable or
developer tools are needed to install or configure it.

To uninstall, remove the app the usual way. The one exception is a phone where
it was made **device owner** (see [Restarting the phone](#restarting-the-phone)):
Android then refuses to uninstall it until ownership is given up, which is done
with the **Remove ownership** button at the bottom of the phone's own screen.

## Restarting the phone

The web UI has a **Restart phone** button. Android gives ordinary apps no way to
reboot the device, so the button only works when andWebCam is the phone's
**device owner** — a management role that Android grants only through device
provisioning (a device management service, or the command below). On a normal
install the button therefore reports that the phone is not managed, and you
restart it with the power button instead. Nothing else in the app depends on it.

To enable it, install andWebCam, enable developer tools, turn on USB debugging, then
run this once, from a machine with a USB cable (the phone must have no accounts, and
this is the only step that needs `adb` and is only required if you want reboot
functionality):

```
adb shell dpm set-device-owner net.andwebcam/.AdminReceiver
```

Once that succeeds the phone screen shows **Remove ownership** next to the words
*disables restart and allows uninstall*. That button hands the role back, after
which the app can be uninstalled normally — so no further `adb` commands are
needed to get rid of it.

## Using it

Open `https://andwebcam.local:8443/` (or the IP shown on the phone).

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

## Notes / quirks

* The bundled TLS certificate is self-signed with SAN `andwebcam.local`
  (`scripts/make-cert.sh`). Because it ships in the APK its private key is not
  secret; it exists to make the stream encrypted and the name verifiable on a
  trusted LAN, not to authenticate the device cryptographically.
* Orientation is applied as the JPEG's EXIF orientation, which browsers and
  ffmpeg honour without any transcoding cost on the phone.
* The service holds a high-performance Wi-Fi lock and a partial wake lock, so
  the stream keeps running at full rate with the screen off.
* A viewer connection can be reset by the network; the hub drops the client
  after three failed writes, the camera closes a few seconds later, and the web
  page or ffmpeg reconnect wakes it up again.

## Developer notes

Building from source:

```
scripts/make-cert.sh          # regenerate the bundled TLS cert (only if needed)
./gradlew assembleDebug
```

Installing straight to a phone on USB (equivalent to tapping the APK, but also
grants the camera permission up front, which is handy for an unattended phone):

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant net.andwebcam android.permission.CAMERA
```

Making the app device owner so the web UI can restart the phone is the
`dpm set-device-owner` command under
[Restarting the phone](#restarting-the-phone). To undo it on a normally
installed copy use the phone's **Remove ownership** button;
`dpm remove-active-admin` refuses with *"Attempt to remove non-test admin"*
unless the app was installed as test-only (`adb install -t`).

