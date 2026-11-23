# camrelay

The public side of andWebCam's outbound proxy. A phone behind CGNAT opens an
**outbound WSS connection** to Apache; Apache proxies a wildcard subdomain to
this daemon on `127.0.0.1:18080`. Every browser request for that subdomain is
sent down the phone's control socket, replayed against the phone's own HTTPS
server, and streamed back. The result is the complete web UI — settings,
recordings, logs and video — reachable remotely without opening any inbound
port on the phone.

Only the Go standard library and `github.com/gorilla/websocket` are used.

## Build

```
go mod tidy
go build -o camrelay .
go test ./...
```

Go 1.21 or newer is required. `make` does the same build; `make install` copies
the binary to `/usr/local/bin/camrelay`.

## Token

The phone authenticates with `Authorization: Bearer <token>` on every
WebSocket. The daemon reads the same token at startup from
`/etc/camrelay/token` (whitespace is trimmed). For local testing you can set
`CAMRELAY_TOKEN`, or point `CAMRELAY_TOKEN_FILE` elsewhere; the env var wins.
Startup fails with a clear message if no token is available.

```
install -d -m 0750 -o util -g util /etc/camrelay
install -m 0640 -o util -g util /path/to/token /etc/camrelay/token
```

Keep the token identical to the one entered in the phone's **Public proxy**
settings. If the token is lost, read it from that settings field (it is masked
but can be copied) or from `publicFeed.token` in `/api/status` as an admin; it
is generated once on first run and is not cleared when the Public URL is
blanked. Viewing passwords are sent by the phone at registration and are
stored only in memory; if one is set, the daemon asks for HTTP Basic auth
before proxying anything for that camera.

## Reverse proxy

A wildcard DNS record such as `*.yourhost.com` must point at the server, and the
TLS certificate must cover it. A single wildcard server block serves every
camera. Both Apache and nginx work; the daemon itself is unchanged.

### Apache

```
<VirtualHost *:443>
    ServerName cam.yourhost.com
    ServerAlias *.yourhost.com

    SSLEngine on
    Include /etc/letsencrypt/options-ssl-apache.conf
    SSLCertificateFile /etc/letsencrypt/live/yourhost-wildcard/fullchain.pem
    SSLCertificateKeyFile /etc/letsencrypt/live/yourhost-wildcard/privkey.pem

    ProxyPreserveHost On
    ProxyPass        /req/ ws://127.0.0.1:18080/req/
    ProxyPass        /ws/  ws://127.0.0.1:18080/ws/
    ProxyPass        /     http://127.0.0.1:18080/
    ProxyPassReverse /     http://127.0.0.1:18080/
    ProxyTimeout     3600
</VirtualHost>
```

`ProxyPreserveHost On` is essential: `cam2.yourhost.com` becomes camera ID
`cam2`. Exact vhosts (`yourhost.com`, `www`, `mail`) take precedence over the
wildcard, so the existing site is unaffected.

The daemon refuses to listen on anything except loopback.

### nginx

The same three requirements apply: preserve the `Host` header so the relay can
derive the camera ID, forward WebSocket upgrades on `/ws/` and `/req/`, and
stream responses without buffering so MJPEG frames arrive as they are produced.

```
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}
server {
    listen 443 ssl;
    server_name cam.yourhost.com *.yourhost.com;
    ssl_certificate     /etc/letsencrypt/live/yourhost-wildcard/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/yourhost-wildcard/privkey.pem;

    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection $connection_upgrade;
    proxy_buffering off;
    proxy_read_timeout 3600;
    proxy_send_timeout 3600;

    location /req/ { proxy_pass http://127.0.0.1:18080/req/; }
    location /ws/  { proxy_pass http://127.0.0.1:18080/ws/;  }
    location /     { proxy_pass http://127.0.0.1:18080/;     }
}
```

This configuration was validated locally against the real relay: Host routing,
WebSocket upgrades on both paths, an HTTP request/response round trip, and
MJPEG frame streaming all worked. It was not deployed as a public nginx host.

## Install as a service

```
make install
install -m 0644 camrelay.service /etc/systemd/system/camrelay.service
systemctl daemon-reload
systemctl enable --now camrelay
journalctl -u camrelay -f
```

The unit runs as `util`, reads the token file and passes
`-listen 127.0.0.1:18080`.

## Protocol

- Phone connects to `/ws/` with the bearer token and sends
  `{"type":"hello","id":"cam2","password":"...","version":1}`. Duplicate live
  IDs are rejected. The daemon replies `hello_ok`.
- For each browser request the daemon sends `{"type":"open","id":N,"method":...,
  "path":...,"query":...,"headers":{...},"contentLength":n}` on the control
  socket.
- The phone opens `/req/N` with the same bearer token. The daemon writes the
  request body as binary messages, then `{"type":"end"}`.
- The phone replies with `{"type":"resp","status":200,"headers":{...}}`, then
  binary messages of response bytes, then `{"type":"end"}`. Responses are
  streamed, so MJPEG works.
- `GET /video` is special: the relay opens **one** upstream stream per camera
  and fans its frames out to every viewer. Multiplying the stream by the number
  of viewers would saturate the phone's uplink.
- The phone pings every ~20 s; the daemon echoes `pong`. No traffic for 70 s
  closes the control socket so a stale registration cannot block a reconnect.

If the phone is offline, the daemon serves a small "Camera offline" page with
a 5-second refresh.

## Renewal

The wildcard certificate was issued with certbot's manual DNS-01 challenge,
so it does **not** renew itself. Repeat the certbot command before expiry,
or install a Namecheap DNS plugin and an auth hook to automate it.
