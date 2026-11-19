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
* **Device owner** — lets the web UI restart the phone, and lets an update install
  without a tap on the phone's screen.

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


## Updating the app

The bottom of the settings pane has **Update software**: pick an APK and it is
installed on the phone, so a new build can be shipped without adb or a cable.
The page reloads itself when the new version answers, usually within a minute.

The same thing from a script:

```
curl -k -u admin:PASSWORD --data-binary @andWebCam.apk \
     https://andwebcam.local:8443/api/update
```

The upload starts immediately; the answer says whether the APK was accepted,
not whether the install finished. The result is shown at the bottom of the
settings pane afterwards (`updateNote` in `/api/status`).

The APK has to be andWebCam, signed with the same key as the installed copy,
and not older, or it is refused with the reason. With device owner the install
happens silently; without it Android puts its normal install dialog on the
phone's screen and somebody has to tap it.

The install replaces the app that is serving the page, so expect the address
to stop answering for about half a minute before the new version comes back.

## Reading the log

Everything the app has written since the phone last booted, without adb:

```
curl -k -u admin:PASSWORD 'https://andwebcam.local:8443/api/log?lines=500'
```

`?all=1` adds the framework's own chatter about the process. The **Download the
log** link at the bottom of the settings pane does the same thing in a browser.

An app may read its own log entries and nobody else's, which covers this app's
messages, its crash traces and the system's record of it being started or
killed. That works the same on a release build, so a remote phone can explain
itself without a debug build or a cable.

## A log file on the phone

The log above only exists while a running app serves it. If an update crashes
before the app answers, there is nothing left to read. To cover that, choose a
folder on the phone (the **Log file** section of the settings pane, or the
**Choose log folder** button on the phone's own screen) and the app keeps a
copy of its log in `andwebcam/andwebcam.log` inside it, growing over time and
rotating at about a megabyte. A crash writes the stack trace and the log up to
that moment as a `crash-*.txt` file in the same place, so even a bad update
leaves a readable log in an ordinary folder, reachable by any file manager or
FTP server on the phone.

Choosing a folder uses the system picker once; the grant survives reboots.
Clearing the folder stops the mirroring but leaves the files already written.

## Recording

The **Recording** pane (the round button in the page's corner) records the
stream to the phone as MP4:

* **Record** starts a file and **Stop** finishes it. The ten seconds before
  the press are included, and only while the camera is already running — the
  phone keeps the frames it was streaming anyway, so recording adds no work
  to the camera.
* Files are written to the `videos` folder next to the `andwebcam` log folder,
  one file per recording, named `video-YYYYMMDD-HHMMSS.mp4` after the moment
  it started.
* **Storage limit** is a percentage of the volume's free space. Older videos
  are deleted to stay under it, and a recording stops by itself when it
  reaches the limit. The pane shows how much room there is and how much the
  videos take.
* The list under the pane plays a video in place; **Delete** removes it.

To fetch one without a browser:

```
curl -k -u admin:PASSWORD -O \
     'https://andwebcam.local:8443/video/file?name=video-20260101-120000.mp4'
```

The same URL as a `<video src>` supports byte ranges, so players seek instead
of downloading the whole file first.

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

