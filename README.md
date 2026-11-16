# andWebCam

A headless-first LAN webcam for Android. The phone captures its camera,
serves it over HTTPS as an MJPEG stream with a control web page, and
advertises itself over mDNS/DNS-SD so other machines can find it without
any manual configuration.

Designed for a phone that you don't have physical access to: it runs as a
foreground service, starts at boot, and is configured via the web UI.

## Download

APKs are at [github.com/ScratchingMyHead/andWebCam/releases](https://github.com/ScratchingMyHead/andWebCam/releases).

## Permissions

* **Camera** — required; the stream cannot start without it.
* **Notifications** (Android 13+) — the service runs as a foreground service and
  needs a visible notification.
* **Ignore battery optimisation** — so the stream keeps running with the screen
  off instead of being dozed, and the phone stays on Wi-Fi. If that dialog was
  skipped, the app's screen offers it again; without it the phone can drop off
  the network while asleep.
* **Device owner** is only required to allow the app to restart the phone.

## Restarting the phone

The web UI has an optional **Restart phone** button. If you really need this
capability **device owner** must be granted via adb:

```
adb shell dpm set-device-owner net.andwebcam/.AdminReceiver
```

## Using it

Open `https://andwebcam.local:8443/` (or the host/IP shown on the phone).

### Authentication

* On first run a random admin password is generated and **shown on the phone
  screen** together with the address. Log in as `admin` with that password.
* Once you change the password in the web UI it is stored as a PBKDF2 hash and
  the phone **stops displaying it**.


## Consuming it on a PC

`/video` needs the login unless anonymous view-only access is on, so the
credentials go in the URL. The certificate is self-signed, so fetch it from the
phone:

```
curl -sk https://andwebcam.local:8443/cert -o andwebcam.pem
```

Then, for example, into a virtual webcam:

```
sudo modprobe v4l2loopback video_nr=10 card_label=andWebCam exclusive_caps=1
ffmpeg -ca_file andwebcam.pem -tls_verify 1 \
       -i https://admin:PASSWORD@andwebcam.local:8443/video \
       -c:v rawvideo -pix_fmt yuv420p -f v4l2 /dev/video10
```

A long-lived stream is safer with ffmpeg's reconnect options, which survive a
Wi-Fi blip instead of exiting:

```
ffmpeg -reconnect 1 -reconnect_streamed 1 -reconnect_delay_max 2 \
       -ca_file andwebcam.pem -tls_verify 1 \
       -i https://admin:PASSWORD@andwebcam.local:8443/video ...
```

Both options have to come before `-i`; without them ffmpeg does not check the
certificate at all.

## Notes / quirks

* The TLS key and certificate are generated on the phone the first time the
  service starts and kept in the app's private files. The certificate is
  self-signed and `CA:FALSE`: browsers have no authority to check it against,
  so they warn, and the only proof that you are talking to this phone is the
  SHA-256 fingerprint on the app's screen matching the one the browser shows.
  It is regenerated if the hostname changes or after ten years.
* Orientation is applied as the JPEG's EXIF orientation, which browsers and
  ffmpeg honour without any transcoding cost on the phone.
* While somebody is watching, the service holds a partial wake lock so the CPU
  stays up for a full-rate stream. The Wi-Fi lock is held for as long as the
  service runs. With no viewers the CPU sleeps again, and every few
  minutes a maintenance alarm wakes it briefly to ask Wi-Fi to reassociate and
  to correct the address if it changed.

