# andWebCam documentation

This document describes the running app: its settings, motion system, storage,
certificates and HTTP API. See [README.md](README.md) for installation and a
quick start.

## Overview

The app is a foreground service with four parts:

- a camera capture thread producing JPEG frames;
- an HTTPS server serving an MJPEG stream, a web UI and JSON endpoints;
- an MP4 recorder with a rolling pre-roll buffer;
- a motion analyser that detects objects, matches them to zones and triggers
  recordings.

The main HTTPS listener defaults to port **8443**. A second listener on port
**8444** can present a Let's Encrypt certificate when one is configured. The
service advertises `<name>.local` over mDNS by default.

The web UI has three panes: **settings** (gear), **recording** and **motion**.
If the admin page path is blank, the admin UI is served at `/`; otherwise `/`
is a bare webcam viewer and the admin UI is at `/<admin path>`.

### Authentication

The username is `admin`. On first run a random password is generated and shown
on the phone screen; after it is changed in the UI it is stored as a PBKDF2
hash and is no longer displayed. The web UI uses a login form; scripts can use
HTTP basic auth or a session cookie. Anonymous view-only access is off by
default.

## Settings reference

### Camera

Most camera settings apply as soon as they are changed.

| Setting | Default | What it does |
| --- | --- | --- |
| Camera | Back | Front or back camera. |
| Resolution | Auto | Any size the camera supports. Changing it restarts the camera. |
| Frame rate | 15 fps | Capture rate; available rates come from the camera. |
| JPEG quality | 80 | 10–100. Higher quality gives larger frames and more network traffic. |
| Zoom | 1× | Digital zoom, up to the camera's maximum. |
| Streaming scale | 50% | 25 / 50 / 75 / 100. Only the viewer's stream is downscaled, and only while somebody is watching. Recordings and motion analysis always use the full camera frame. Lower values reduce the phone's encoding and network load. |
| Orientation | Landscape | Landscape, Portrait, Landscape 180 or Portrait 270. Applied as EXIF rotation, so browsers and ffmpeg need no transcoding. |
| Torch / LED | Off | Turns the camera flash on where it is available. |

### Access

| Setting | Default | What it does |
| --- | --- | --- |
| Allow anonymous view-only access | Off | Lets anyone on the LAN watch the stream and read status without logging in. All controls remain admin-only. |
| Admin page path | Random on first run | 1–32 lowercase letters, digits or hyphens. A blank value puts the admin UI at `/` and disables the public viewer. Changing it changes the URL used to reach the admin page. |

The **Public proxy** section has three further settings:

| Setting | Default | What it does |
| --- | --- | --- |
| Public URL | Empty | The public address of this camera, e.g. `https://cam2.yourhost.com/`. Blank disables the proxy. |
| Proxy token | Generated on first run | Shared secret sent to the relay. Copy it into the relay's token file; the relay rejects a different token. |
| Viewing password | Empty | If set, the relay asks for HTTP Basic auth before serving this camera. Blank means no relay-level password; the app's own admin password still applies. |

### Public proxy

andWebCam is able to make an **outbound** HTTPS connection, so no inbound ports
or port forwarding are needed and the camera works behind CGNAT. However, this
requires an external Apache server, so forwarding a port to the phone is
preferable if that option is available.

Apache on the public host terminates TLS for a wildcard subdomain and forwards
requests to a small relay daemon on loopback. The relay asks the phone to replay
each request against the phone's own local web server and streams the response
back. The whole UI is available remotely, not just the video. Apache is used
in the example setup, but nginx works too with the equivalent proxy rules in
the relay's README.

Each camera has its own hostname: `cam2.yourhost.com` maps to camera ID `cam2`,
and a single wildcard Apache vhost serves all cameras. When the phone is
offline the relay serves a short "Camera offline" page with a five-second
refresh. While the proxy is enabled the camera stays open; no data is sent
until a browser connects through it.

The relay lives in `tools/camrelay` in the source tree and has its own README
covering the Apache configuration, the systemd unit and the wire protocol. The
wildcard certificate uses Let's Encrypt DNS-01 and must be renewed with the
same certbot command — automatic renewal needs a DNS provider plugin and an
auth hook.

As with any remote admin access, use a strong admin password and keep the
proxy token private. The relay adds an optional viewing password in front of
the whole site; the app's own login remains the second layer.

### Service

These take effect when **Apply service settings** is pressed.

| Setting | Default | What it does |
| --- | --- | --- |
| Name | `andwebcam` | The mDNS hostname, `<name>.local`. |
| HTTPS port | 8443 | The main listener. Changing it changes the URL used to reach the app. |
| Start streaming when the service starts | On | If off, streaming is started from the web UI. |
| Advertise on the LAN (mDNS) | On | Publishes `<name>.local`. Turning it off stops the responder immediately. |

### Recording pane

| Setting | Default | What it does |
| --- | --- | --- |
| Cap, percent of free space | 10 | Recordings may use this percentage of the volume's free space. Oldest videos are deleted first; a running recording stops if it cannot stay under the cap. |

**Record** starts a file and **Stop** finishes it. The recorded file includes
the previous 10 seconds because the phone keeps a rolling buffer of the frames
it was already streaming. Recording needs streaming to be on; turning streaming
off ends a running recording. The storage location is chosen once on the phone
screen. Files are written to `video/` inside it as
`video-YYYYMMDD-HHMMSS.mp4`, or `zoneN-YYYYMMDD-HHMMSS.mp4` for motion
recordings. The pane lists, plays and deletes them.

### Motion pane

| Setting | Default | What it does |
| --- | --- | --- |
| Detector | Cell grid | **Cell grid** is the built-in detector. **Object tracking** uses OpenCV; it needs the arm64 native library, and the app falls back to the grid with a visible error if the library cannot load. |
| Reject lighting changes | On | OpenCV only. Rejects changes that keep the background's texture, such as headlights, reflections and exposure steps. |
| Pre-roll buffer size | 10 s | 0–30 seconds of frames kept in memory before a trigger and included in motion recordings. Larger values use more RAM. |
| Post-motion recording | 30 s | 5–300 seconds of quiet after the last motion before a motion recording ends. |
| Sensitivity | 85 | 0–100, higher is more sensitive. For the grid it is the brightness change a cell needs. For OpenCV it sets the foreground threshold and the smallest blob considered, so raising it lets smaller, more distant objects be detected at the cost of more noise. |
| Grid count | 32 | Grid only. Cells across the long edge of the frame; higher values see finer detail and more sensor noise. |
| Activation zones | None | Polygons drawn on the video. Each zone has an object-size range; an object inside the zone and within that range starts a recording. |

**Start motion** arms detection. Arming survives restarts as long as a storage
location has been chosen. While the motion pane is open its polling holds a
10-second camera lease, so boxes are drawn even when motion is disarmed; the
poll refreshes the lease and closing the pane releases the camera and wake
lock.

## Motion detection in detail

Analysis runs roughly five times a second on a 320-pixel-wide frame, regardless
of the stream's resolution or scale.

**Cell grid** divides the frame into cells, compares each cell against a
background estimate and groups changed cells into objects.

**Object tracking** uses OpenCV: MOG2 background subtraction, a small opening
and dilation plus a horizontal closing to rejoin objects that pass behind thin
foreground stems, contour filtering, the lighting gate, and centre-distance
tracking between ticks. Boxes carry an id that identifies the objects currently
on screen; ids restart when nothing is being tracked.

### Zones and object size

Zones are polygons in normalized frame coordinates, so they keep working when
the camera resolution changes. Each zone's single slider has two handles:

- the light handle is the **minimum** object size (always at least 1);
- the dark handle is the **maximum**; **100 means no upper limit**.

Dragging one handle into the other carries it along, keeping a small gap. The
zone's live level is the largest object currently inside it, and the zone fires
on that same value — so the fill turning red corresponds to a recording
starting. Object boxes are red only when that object would trigger a zone;
otherwise they are cyan. The box id is drawn above the box.

The size scale is logarithmic between the smallest detectable blob and 12% of
the frame, giving useful resolution at the small end. Because zones are
sampled several times a second, a fast object can cross a very small zone
between samples; make zones large enough along the expected path.

Zones are stored as `aspect;min:max:x,y,...;...`. Older zone strings with no
maximum are read as `100`.

### Debug table

The debug table under the zone list is hidden until **Show debug** is pressed.
Newest events are first. Its columns are:

| Column | Meaning |
| --- | --- |
| time | Time of the analysis tick. |
| all | Overall motion level. |
| chg | Changed cells / cells compared. |
| shift | Frame shift. |
| peak | Peak pixel change. |
| big | Largest level in any zone. |
| light | Lighting and shadow rejections. |
| zones | Largest level in each zone, e.g. `z1=0 z2=12`. |
| reject | Zones rejecting that object on size, with the size in slider units, e.g. `z3 47 (>)` or `z3 2 (<)`. |
| match | Zones whose size criteria were met. |
| trig | Zones that actually started a recording. |

A line is recorded when the overall level rises by more than two points, and
always when a recording starts.

## HTTPS and certificates

On first start the phone generates a small self-signed root authority and a
server certificate signed by it. Browsers warn about the self-signed identity;
the SHA-256 fingerprint shown on the phone screen is the way to confirm it.
`/cert` serves the root PEM, which can be trusted once on a computer to remove
the warning. A new identity is generated if the hostname changes or after ten
years.

The **Public certificate** section configures an optional Let's Encrypt
certificate and DuckDNS updates:

| Setting | Default | What it does |
| --- | --- | --- |
| Let's Encrypt certificate | On | Issues and serves a public certificate on the public HTTPS port. Turning it off immediately returns the main listener to the self-signed identity. |
| DuckDNS address updates | On | Keeps the DuckDNS A record pointed at the phone. Independent of the certificate. |
| Domain | Empty | DNS name the certificate is issued for. |
| DuckDNS token | Empty | API token; never sent to a browser. |
| Validation | DNS (DuckDNS TXT) | DNS-01, or HTTP-01 with an external port 80 forwarded to the HTTP challenge port. |
| Publish address | Phone's LAN address | Which address DuckDNS publishes: LAN, WAN or none. |
| Use staging | Off | Use Let's Encrypt's test certificates. |
| HTTP challenge port | 8080 | Internal port for HTTP-01 validation. |
| Public HTTPS port | 8444 | Listener that presents the Let's Encrypt certificate. |

**Save certificate settings** stores the configuration; **Renew certificate**
requests or renews the certificate. Renewal and on/off switches take effect
without restarting the server, so logged-in sessions survive.

## Logs, storage and updates

The app can read its own log entries, so without adb:

```
curl -k -u admin:PASSWORD 'https://andwebcam.local:8443/api/log?lines=500'
```

Add `?all=1` for the framework's own messages. **Download log** in the settings
pane does the same thing in a browser.

Choosing a storage location on the phone starts a mirrored log at
`logs/andwebcam.log`, which grows over time and rotates at about a megabyte. A
crash also writes `crash-*.txt` in the same folder. The grant survives reboots;
clearing the location stops mirroring but leaves the files already written.

**Update software** installs an uploaded APK, or:

```
curl -k -u admin:PASSWORD --data-binary @andWebCam.apk \
     https://andwebcam.local:8443/api/update
```

The APK must be andWebCam, signed with the same key and not older. With device
owner it installs silently; otherwise Android shows its install dialog on the
phone. The result is kept across the restart and shown in the settings pane.
Expect the address to stop answering for around half a minute.

**Restart service** restarts the app. **Restart phone** needs device owner:

```
adb shell dpm set-device-owner net.andwebcam/.AdminReceiver
```

## HTTP API

Most endpoints require the admin role. Viewer-safe ones work with anonymous
access enabled or with viewer credentials.

| Endpoint | Purpose |
| --- | --- |
| `GET /` | Public viewer when the admin path is set; otherwise the admin page. |
| `GET /<admin path>` | Admin page shell. |
| `GET /video` | MJPEG stream. |
| `GET /snapshot` | One JPEG; requires streaming to be on. |
| `GET /cert` | Self-signed root certificate in PEM format. |
| `GET /api/status` | Camera state. Admins also get version, build and admin path. |
| `POST /api/login`, `POST /api/logout` | Session login and logout. |
| `POST /api/settings` | Apply camera and service settings. |
| `POST /api/password` | Change the admin password. |
| `POST /api/cert/save`, `POST /api/cert/issue` | Save certificate settings, renew the certificate. |
| `GET /api/cert`, `GET /api/cert/pem` | Certificate status, self-signed public certificate. |
| `POST /api/service/restart`, `POST /api/reboot` | Restart the app or the phone. |
| `GET /api/log?lines=N&all=1` | Log lines from this boot. |
| `POST /api/diag` | Camera diagnostic report (`mode`, `seconds`, `resolution`). |
| `POST /api/update` | Install an APK sent as the raw request body. |
| `GET /api/record/list` | Recording list and storage usage. |
| `POST /api/record/start`, `POST /api/record/stop` | Manual recording. |
| `POST /api/record/cap` | Recording cap (`percent`). |
| `POST /api/record/delete`, `POST /api/record/delete-all` | Delete recordings (`name`). |
| `GET /video/file?name=FILE` | Download a recording; supports byte ranges. |
| `POST /api/motion/set` | Arm or disarm motion (`enabled`). |
| `GET /api/motion/list?since=MS` | Zones, live boxes and events since a millisecond timestamp. Also refreshes the motion pane's camera lease. |
| `POST /api/motion/zones` | Replace the zone specification as plain text. |
| `POST /api/motion/quiet`, `POST /api/motion/preroll` | Quiet period and pre-roll buffer (`seconds`). |
| `POST /api/motion/level`, `POST /api/motion/grid` | Sensitivity (`level`) and grid count (`grid`). |
| `POST /api/motion/detector` | Detector (`detector`) and lighting gate (`lightGate`). |
| `POST /api/motion/preview` | Hold or release a camera preview lease (`on`). |

## Consuming the stream on a computer

Fetch and trust the phone's root certificate, then use ffmpeg. The reconnect
options and certificate options must come before `-i`:

```
curl -sk https://andwebcam.local:8443/cert -o andwebcam.pem
ffmpeg -ca_file andwebcam.pem -tls_verify 1 \
       -reconnect 1 -reconnect_streamed 1 -reconnect_delay_max 2 \
       -i https://admin:PASSWORD@andwebcam.local:8443/video \
       -c:v rawvideo -pix_fmt yuv420p -f v4l2 /dev/video10
```

MJPEG needs no transcoding on the phone. With `/` as the public viewer, the
control bar is hidden until the pointer moves or the screen is tapped, and
auto-hides again after a few seconds.
