package main

import (
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// One upstream request per camera and stream path feeds every viewer of that
// stream. Each viewer gets the response headers once and a bounded channel of
// chunks; a slow viewer drops old chunks instead of holding up the camera.
type streamClient struct {
	headers chan responseMsg
	chunks  chan []byte
}

type streamHub struct {
	id      int64
	path    string
	mu      sync.Mutex
	clients map[*streamClient]struct{}
	headers *responseMsg
	done    chan struct{}
	once    sync.Once
}

func (h *streamHub) finish() { h.once.Do(func() { close(h.done) }) }

func (h *streamHub) add(c *streamClient) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.clients[c] = struct{}{}
	if h.headers != nil {
		c.headers <- *h.headers
	}
}

// remove returns true when the last viewer has gone.
func (h *streamHub) remove(c *streamClient) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	delete(h.clients, c)
	return len(h.clients) == 0
}

func (h *streamHub) setHeaders(m responseMsg) {
	h.mu.Lock()
	h.headers = &m
	for c := range h.clients {
		select {
		case c.headers <- m:
		default:
		}
	}
	h.mu.Unlock()
}

func (h *streamHub) broadcast(chunk []byte) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for c := range h.clients {
		select {
		case c.chunks <- chunk:
		default:
			select {
			case <-c.chunks:
			default:
			}
			select {
			case c.chunks <- chunk:
			default:
			}
		}
	}
}

// getOrCreateStream returns the hub for one path on this camera, creating it
// when nobody is watching yet.
func (cam *camera) getOrCreateStream(r *relay, path string) (*streamHub, bool) {
	cam.streamMu.Lock()
	defer cam.streamMu.Unlock()
	if cam.streams == nil {
		cam.streams = make(map[string]*streamHub)
	}
	if h := cam.streams[path]; h != nil {
		return h, false
	}
	h := &streamHub{
		id:      r.next.Add(1),
		path:    path,
		clients: make(map[*streamClient]struct{}),
		done:    make(chan struct{}),
	}
	cam.streams[path] = h
	return h, true
}

func (cam *camera) clearStream(path string, h *streamHub) {
	cam.streamMu.Lock()
	if cam.streams[path] == h {
		delete(cam.streams, path)
	}
	cam.streamMu.Unlock()
}

// streamUpstream owns the single request socket that carries a stream from the
// phone. It publishes headers and chunks to the hub and closes it when done.
func (r *relay) streamUpstream(cam *camera, h *streamHub, headers map[string]string) {
	name := strings.TrimPrefix(h.path, "/")
	log.Printf("cam %s: %s upstream starting id=%d", cam.id, name, h.id)
	msg := openMsg{
		Type: "open", ID: h.id,
		Method: http.MethodGet, Path: h.path,
		Headers: headers,
	}
	phone, cleanup, err := r.openUpstream(cam, msg)
	if err != nil {
		log.Printf("cam %s: %s upstream failed: %v", cam.id, name, err)
		h.finish()
		cam.clearStream(h.path, h)
		return
	}
	defer cleanup()
	defer phone.Close()
	defer h.finish()
	defer cam.clearStream(h.path, h)

	// When the last browser viewer leaves (or times out), finish() closes
	// h.done. Close the upstream socket too, or the phone keeps streaming
	// into a hub that nobody is reading.
	watching := make(chan struct{})
	defer close(watching)
	go func() {
		select {
		case <-h.done:
			phone.Close()
		case <-watching:
		}
	}()

	_ = phone.SetWriteDeadline(time.Now().Add(10 * time.Second))
	_ = phone.WriteMessage(websocket.TextMessage, []byte(`{"type":"end"}`))

	_ = phone.SetReadDeadline(time.Now().Add(90 * time.Second))
	chunks := 0
	for {
		mt, data, err := phone.ReadMessage()
		_ = phone.SetReadDeadline(time.Now().Add(90 * time.Second))
		if err != nil {
			log.Printf("cam %s: %s upstream ended: %v (%d chunks)", cam.id, name, err, chunks)
			return
		}
		if mt == websocket.BinaryMessage {
			if chunks == 0 {
				log.Printf("cam %s: %s upstream first chunk (%d bytes)", cam.id, name, len(data))
			}
			chunks++
			h.broadcast(data)
			continue
		}
		var rm responseMsg
		if json.Unmarshal(data, &rm) != nil {
			continue
		}
		switch rm.Type {
		case "resp":
			h.setHeaders(rm)
		case "end":
			return
		}
	}
}

// handleSharedStream attaches one browser viewer to the camera's stream hub
// for this request path.
func (r *relay) handleSharedStream(w http.ResponseWriter, req *http.Request, cam *camera, headers map[string]string) {
	path := req.URL.Path
	name := strings.TrimPrefix(path, "/")
	h, fresh := cam.getOrCreateStream(r, path)
	log.Printf("cam %s: %s viewer attached (fresh=%v, hub=%d)", cam.id, name, fresh, h.id)
	if fresh {
		go r.streamUpstream(cam, h, headers)
	}

	c := &streamClient{
		headers: make(chan responseMsg, 1),
		// The phone sends a JPEG in several 16 KB chunks. A two-slot queue
		// drops fragments of the same frame, so browsers never see a whole
		// image; this holds a few seconds of chunks instead. Sound arrives
		// in 20 ms pieces, where the same queue just means more slack.
		chunks: make(chan []byte, 256),
	}
	h.add(c)
	defer func() {
		if h.remove(c) {
			h.finish()
			cam.clearStream(path, h)
		}
	}()

	var first responseMsg
	select {
	case first = <-c.headers:
	case <-h.done:
		http.Error(w, "camera offline", http.StatusServiceUnavailable)
		return
	case <-time.After(15 * time.Second):
		http.Error(w, "no data from camera", http.StatusGatewayTimeout)
		return
	}

	copyHeaders(w.Header(), first.Headers)
	status := first.Status
	if status <= 0 {
		status = http.StatusOK
	}
	w.WriteHeader(status)
	flusher, _ := w.(http.Flusher)
	if flusher != nil {
		flusher.Flush()
	}

	// Video is cut after two minutes because Apache never tells the backend
	// when a browser goes away; the page reopens the stream before then.
	// Sound is continuous: its writes fail within a frame or two of a
	// disconnect, so it needs no deadline of its own and must not be cut.
	var deadline <-chan time.Time
	if path == "/video" {
		t := time.NewTimer(120 * time.Second)
		defer t.Stop()
		deadline = t.C
	}
	for {
		select {
		case chunk := <-c.chunks:
			_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(30 * time.Second))
			if _, err := w.Write(chunk); err != nil {
				return
			}
			if flusher != nil {
				flusher.Flush()
			}
		case <-h.done:
			// The upstream can finish just after publishing its last chunks;
			// flush anything already queued before closing the viewer.
			for {
				select {
				case chunk := <-c.chunks:
					_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(30 * time.Second))
					if _, err := w.Write(chunk); err != nil {
						return
					}
					if flusher != nil {
						flusher.Flush()
					}
				default:
					return
				}
			}
		case <-deadline:
			return
		}
	}
}

// copyHeaders drops hop-by-hop fields that the backend must not set.
func copyHeaders(dst http.Header, src map[string]string) {
	for k, v := range src {
		switch strings.ToLower(k) {
		case "content-length", "transfer-encoding", "connection", "upgrade":
			continue
		}
		dst.Set(k, v)
	}
}
