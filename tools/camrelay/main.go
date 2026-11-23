// Command camrelay lets an andWebCam phone behind CGNAT serve its whole web UI
// through an outbound HTTPS connection. Apache terminates TLS for a wildcard
// subdomain and proxies each camera to this daemon on loopback; the phone opens
// a control WebSocket and, for each browser request, a short-lived request
// WebSocket whose bytes are relayed to the phone's own HTTPS server.
package main

import (
	"crypto/subtle"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

const (
	defaultListen   = "127.0.0.1:18080"
	defaultTokenFile = "/etc/camrelay/token"
	maxBodyBytes    = 48 << 20 // enough for an APK update
	chunkSize       = 32 << 10
	pairTimeout     = 15 * time.Second
	helloTimeout    = 15 * time.Second
	phoneTimeout    = 70 * time.Second
)

var idRE = regexp.MustCompile(`^[a-z0-9-]{1,32}$`)

// ---------------------------------------------------------------------------
// Wire types
// ---------------------------------------------------------------------------

type helloMsg struct {
	Type     string `json:"type"`
	ID       string `json:"id"`
	Password string `json:"password"`
	Version  int    `json:"version"`
}

type openMsg struct {
	Type          string            `json:"type"`
	ID            int64             `json:"id"`
	Method        string            `json:"method"`
	Path          string            `json:"path"`
	Query         string            `json:"query"`
	Headers       map[string]string `json:"headers"`
	ContentLength int               `json:"contentLength"`
}

type responseMsg struct {
	Type    string            `json:"type"`
	Status  int               `json:"status,omitempty"`
	Headers map[string]string `json:"headers,omitempty"`
}

type ackMsg struct {
	Type string      `json:"type"`
	T    json.RawMessage `json:"t,omitempty"`
}

type errorMsg struct {
	Type    string `json:"type"`
	Message string `json:"message"`
}

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

type camera struct {
	id       string
	password string

	writeMu sync.Mutex
	conn    *websocket.Conn

	mu       sync.Mutex
	online   atomic.Bool
	lastSeen time.Time

	// Request sockets are short-lived but a viewer's MJPEG stream is one of
	// them. They must be closed when the phone leaves or the browser sits on
	// a connection that never ends.
	reqMu sync.Mutex
	reqs  map[*websocket.Conn]struct{}
}

func (cam *camera) addReq(c *websocket.Conn) {
	cam.reqMu.Lock()
	defer cam.reqMu.Unlock()
	if cam.reqs == nil {
		cam.reqs = make(map[*websocket.Conn]struct{})
	}
	cam.reqs[c] = struct{}{}
}

func (cam *camera) removeReq(c *websocket.Conn) {
	cam.reqMu.Lock()
	defer cam.reqMu.Unlock()
	delete(cam.reqs, c)
}

func (cam *camera) closeReqs() {
	cam.reqMu.Lock()
	open := make([]*websocket.Conn, 0, len(cam.reqs))
	for c := range cam.reqs {
		open = append(open, c)
	}
	cam.reqs = nil
	cam.reqMu.Unlock()
	for _, c := range open {
		c.Close()
	}
}

type pendingReq struct {
	id    int64
	cam   *camera
	paired chan *websocket.Conn
	done   chan struct{}
}

type relay struct {
	mu    sync.Mutex
	cams  map[string]*camera
	pend  map[int64]*pendingReq
	next  atomic.Int64
	token string

	upgrader websocket.Upgrader
}

func newRelay(token string) *relay {
	return &relay{
		cams:  make(map[string]*camera),
		pend:  make(map[int64]*pendingReq),
		token: token,
		upgrader: websocket.Upgrader{
			ReadBufferSize:  4096,
			WriteBufferSize: 4096,
			CheckOrigin:     func(*http.Request) bool { return true },
		},
	}
}

func (r *relay) phoneAuth(req *http.Request) bool {
	h := req.Header.Get("Authorization")
	return subtle.ConstantTimeCompare([]byte(h), []byte("Bearer "+r.token)) == 1
}

func sendJSON(cam *camera, v any) error {
	cam.writeMu.Lock()
	defer cam.writeMu.Unlock()
	if cam.conn == nil {
		return fmt.Errorf("phone offline")
	}
	_ = cam.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	return cam.conn.WriteJSON(v)
}

// A camera subdomain such as cam2.example.com identifies the camera directly.
// On a plain hostname the caller can use ?cam=cam2 instead.
func cameraIDFromHost(host string) string {
	if i := strings.LastIndex(host, ":"); i >= 0 {
		host = host[:i]
	}
	if net.ParseIP(host) != nil {
		return ""
	}
	parts := strings.Split(host, ".")
	if len(parts) >= 3 {
		return parts[0]
	}
	return ""
}

func (r *relay) cameraFor(req *http.Request) (*camera, bool) {
	id := cameraIDFromHost(req.Host)
	if id == "" {
		id = req.URL.Query().Get("cam")
	}
	if !idRE.MatchString(id) {
		return nil, false
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	cam, ok := r.cams[id]
	return cam, ok
}

func viewerAuthorized(req *http.Request, cam *camera) bool {
	if cam.password == "" {
		return true
	}
	u, p, ok := req.BasicAuth()
	_ = u
	return ok && subtle.ConstantTimeCompare([]byte(p), []byte(cam.password)) == 1
}

// ---------------------------------------------------------------------------
// HTTP entry point
// ---------------------------------------------------------------------------

func (r *relay) ServeHTTP(w http.ResponseWriter, req *http.Request) {
	p := req.URL.Path
	switch {
	case p == "/ws/":
		r.handleControl(w, req)
	case strings.HasPrefix(p, "/req/"):
		r.handleRequestSocket(w, req, strings.TrimPrefix(p, "/req/"))
	default:
		r.handleProxy(w, req)
	}
}

const offlinePage = `<!doctype html><meta charset="utf-8"><title>Camera offline</title>
<style>body{background:#111;color:#ccc;font:16px system-ui;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
div{text-align:center}</style><div><h2>Camera offline</h2><p>Waiting for the camera to reconnect…</p>
<p><a href="/">Retry</a></p></div><meta http-equiv="refresh" content="5">`

func (r *relay) handleProxy(w http.ResponseWriter, req *http.Request) {
	cam, ok := r.cameraFor(req)
	if !ok || !cam.online.Load() {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = io.WriteString(w, offlinePage)
		return
	}
	if !viewerAuthorized(req, cam) {
		w.Header().Set("WWW-Authenticate", `Basic realm="andWebCam"`)
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	body, err := io.ReadAll(io.LimitReader(req.Body, maxBodyBytes+1))
	if err != nil || len(body) > maxBodyBytes {
		http.Error(w, "request body too large", http.StatusRequestEntityTooLarge)
		return
	}

	headers := make(map[string]string)
	for k, v := range req.Header {
		if len(v) > 0 {
			headers[k] = v[0]
		}
	}
	// If the relay gate used its own Basic password, do not pass that header
	// on to the app: it would be mistaken for the admin login. The app then
	// issues its own challenge as normal.
	if cam.password != "" {
		delete(headers, "Authorization")
	}

	id := r.next.Add(1)
	pr := &pendingReq{id: id, cam: cam, paired: make(chan *websocket.Conn, 1), done: make(chan struct{})}
	r.mu.Lock()
	r.pend[id] = pr
	r.mu.Unlock()
	defer func() {
		r.mu.Lock()
		delete(r.pend, id)
		r.mu.Unlock()
		close(pr.done)
	}()

	msg := openMsg{
		Type:          "open",
		ID:            id,
		Method:        req.Method,
		Path:          req.URL.Path,
		Query:         req.URL.RawQuery,
		Headers:       headers,
		ContentLength: len(body),
	}
	if err := sendJSON(cam, msg); err != nil {
		http.Error(w, "phone went offline", http.StatusBadGateway)
		return
	}

	var phone *websocket.Conn
	select {
	case phone = <-pr.paired:
	case <-time.After(pairTimeout):
		http.Error(w, "phone did not open a request socket", http.StatusGatewayTimeout)
		return
	}
	defer phone.Close()
	// When the browser goes away (timeout, tab closed), stop reading from the
	// phone. Otherwise Apache workers and phone viewers accumulate forever.
	finished := make(chan struct{})
	defer close(finished)
	go func() {
		select {
		case <-req.Context().Done():
			phone.Close()
		case <-finished:
		}
	}()

	for off := 0; off < len(body); off += chunkSize {
		end := off + chunkSize
		if end > len(body) {
			end = len(body)
		}
		_ = phone.SetWriteDeadline(time.Now().Add(10 * time.Second))
		if err := phone.WriteMessage(websocket.BinaryMessage, body[off:end]); err != nil {
			http.Error(w, "request body failed", http.StatusBadGateway)
			return
		}
	}
	_ = phone.SetWriteDeadline(time.Now().Add(10 * time.Second))
	_ = phone.WriteMessage(websocket.TextMessage, []byte(`{"type":"end"}`))

	wroteHeader := false
	flusher, _ := w.(http.Flusher)
	for {
		mt, data, err := phone.ReadMessage()
		if err != nil {
			if !wroteHeader {
				http.Error(w, "phone connection failed", http.StatusBadGateway)
			}
			return
		}
		if mt == websocket.BinaryMessage {
			if !wroteHeader {
				w.WriteHeader(http.StatusOK)
				wroteHeader = true
			}
			_, _ = w.Write(data)
			if flusher != nil {
				flusher.Flush()
			}
			continue
		}
		var rm responseMsg
		if json.Unmarshal(data, &rm) != nil {
			continue
		}
		switch rm.Type {
		case "resp":
			if wroteHeader {
				continue
			}
			for k, v := range rm.Headers {
				switch strings.ToLower(k) {
				case "content-length", "transfer-encoding", "connection", "upgrade":
					continue
				}
				w.Header().Set(k, v)
			}
			status := rm.Status
			if status <= 0 {
				status = http.StatusOK
			}
			w.WriteHeader(status)
			wroteHeader = true
			if flusher != nil {
				flusher.Flush()
			}
		case "end":
			return
		}
	}
}

// ---------------------------------------------------------------------------
// WebSocket handlers
// ---------------------------------------------------------------------------

func (r *relay) handleControl(w http.ResponseWriter, req *http.Request) {
	if !r.phoneAuth(req) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	conn, err := r.upgrader.Upgrade(w, req, nil)
	if err != nil {
		return
	}
	_ = conn.SetReadDeadline(time.Now().Add(helloTimeout))
	var hello helloMsg
	if err := conn.ReadJSON(&hello); err != nil || hello.Type != "hello" ||
		!idRE.MatchString(hello.ID) {
		_ = conn.WriteJSON(errorMsg{Type: "error", Message: "bad hello"})
		conn.Close()
		return
	}

	cam := &camera{id: hello.ID, password: hello.Password, conn: conn, lastSeen: time.Now()}
	cam.online.Store(true)
	r.mu.Lock()
	if old, exists := r.cams[hello.ID]; exists && old.online.Load() {
		// A half-open socket can leave a stale registration behind. Let the
		// new connection take over rather than locking the camera out.
		log.Printf("cam %s: taking over stale registration", hello.ID)
		old.online.Store(false)
		old.closeReqs()
		old.writeMu.Lock()
		oldConn := old.conn
		old.conn = nil
		old.writeMu.Unlock()
		if oldConn != nil {
			oldConn.Close()
		}
	}
	r.cams[hello.ID] = cam
	r.mu.Unlock()
	log.Printf("cam %s: phone connected", cam.id)
	_ = sendJSON(cam, ackMsg{Type: "hello_ok"})
	defer func() {
		cam.writeMu.Lock()
		cam.conn = nil
		cam.writeMu.Unlock()
		r.mu.Lock()
		if r.cams[hello.ID] == cam {
			delete(r.cams, hello.ID)
		}
		r.mu.Unlock()
		cam.online.Store(false)
		cam.closeReqs()
		log.Printf("cam %s: phone disconnected", cam.id)
		conn.Close()
	}()

	for {
		_ = conn.SetReadDeadline(time.Now().Add(phoneTimeout))
		_, data, err := conn.ReadMessage()
		if err != nil {
			return
		}
		var m struct {
			Type string          `json:"type"`
			T    json.RawMessage `json:"t"`
		}
		if json.Unmarshal(data, &m) != nil {
			continue
		}
		cam.mu.Lock()
		cam.lastSeen = time.Now()
		cam.mu.Unlock()
		if m.Type == "ping" {
			_ = sendJSON(cam, ackMsg{Type: "pong", T: m.T})
		}
	}
}

func (r *relay) handleRequestSocket(w http.ResponseWriter, req *http.Request, idText string) {
	if !r.phoneAuth(req) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	id, err := strconv.ParseInt(idText, 10, 64)
	if err != nil {
		http.NotFound(w, req)
		return
	}
	camID := cameraIDFromHost(req.Host)
	if camID == "" {
		camID = req.URL.Query().Get("cam")
	}
	r.mu.Lock()
	pr := r.pend[id]
	r.mu.Unlock()
	if pr == nil || pr.cam.id != camID {
		http.NotFound(w, req)
		return
	}
	conn, err := r.upgrader.Upgrade(w, req, nil)
	if err != nil {
		return
	}
	pr.cam.addReq(conn)
	defer pr.cam.removeReq(conn)
	select {
	case pr.paired <- conn:
	case <-pr.done:
		conn.Close()
		return
	}
	<-pr.done
	conn.Close()
}

// ---------------------------------------------------------------------------

func loadToken() (string, error) {
	if t := strings.TrimSpace(os.Getenv("CAMRELAY_TOKEN")); t != "" {
		return t, nil
	}
	path := os.Getenv("CAMRELAY_TOKEN_FILE")
	if path == "" {
		path = defaultTokenFile
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("read token file %s: %w", path, err)
	}
	t := strings.TrimSpace(string(b))
	if t == "" {
		return "", fmt.Errorf("token file %s is empty", path)
	}
	return t, nil
}

func main() {
	listen := flag.String("listen", defaultListen, "loopback address to listen on")
	flag.Parse()
	if !strings.HasPrefix(*listen, "127.0.0.1:") && !strings.HasPrefix(*listen, "localhost:") {
		log.Fatal("refusing to listen on a non-loopback address")
	}
	token, err := loadToken()
	if err != nil {
		log.Fatal(err)
	}
	relay := newRelay(token)
	log.Printf("camrelay listening on %s", *listen)
	server := &http.Server{
		Addr:              *listen,
		Handler:           relay,
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Fatal(server.ListenAndServe())
}
