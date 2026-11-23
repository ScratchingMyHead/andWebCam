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

// One upstream /video request per camera feeds all viewers. Each viewer gets
// the response headers once and a bounded channel of frames; a slow viewer
// drops old frames instead of holding up the camera.
type videoClient struct {
	headers chan responseMsg
	frames  chan []byte
}

type videoHub struct {
	id      int64
	mu      sync.Mutex
	clients map[*videoClient]struct{}
	headers *responseMsg
	done    chan struct{}
	once    sync.Once
}

func (h *videoHub) finish() { h.once.Do(func() { close(h.done) }) }

func (h *videoHub) add(c *videoClient) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.clients[c] = struct{}{}
	if h.headers != nil {
		c.headers <- *h.headers
	}
}

// remove returns true when the last viewer has gone.
func (h *videoHub) remove(c *videoClient) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	delete(h.clients, c)
	return len(h.clients) == 0
}

func (h *videoHub) setHeaders(m responseMsg) {
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

func (h *videoHub) broadcast(frame []byte) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for c := range h.clients {
		select {
		case c.frames <- frame:
		default:
			select {
			case <-c.frames:
			default:
			}
			select {
			case c.frames <- frame:
			default:
			}
		}
	}
}

func (cam *camera) getOrCreateVideo(r *relay, headers map[string]string) (*videoHub, bool) {
	cam.videoMu.Lock()
	defer cam.videoMu.Unlock()
	if cam.video != nil {
		return cam.video, false
	}
	h := &videoHub{
		id:      r.next.Add(1),
		clients: make(map[*videoClient]struct{}),
		done:    make(chan struct{}),
	}
	cam.video = h
	return h, true
}

func (cam *camera) clearVideo(h *videoHub) {
	cam.videoMu.Lock()
	if cam.video == h {
		cam.video = nil
	}
	cam.videoMu.Unlock()
}

// videoUpstream owns the single request socket that carries frames from the
// phone. It publishes headers/frames to the hub and closes it when finished.
func (r *relay) videoUpstream(cam *camera, h *videoHub, headers map[string]string) {
	msg := openMsg{
		Type: "open", ID: h.id,
		Method: http.MethodGet, Path: "/video",
		Headers: headers,
	}
	phone, cleanup, err := r.openUpstream(cam, msg)
	if err != nil {
		log.Printf("cam %s: video upstream failed: %v", cam.id, err)
		h.finish()
		cam.clearVideo(h)
		return
	}
	defer cleanup()
	defer phone.Close()
	defer h.finish()
	defer cam.clearVideo(h)

	_ = phone.SetWriteDeadline(time.Now().Add(10 * time.Second))
	_ = phone.WriteMessage(websocket.TextMessage, []byte(`{"type":"end"}`))

	_ = phone.SetReadDeadline(time.Now().Add(90 * time.Second))
	for {
		mt, data, err := phone.ReadMessage()
		_ = phone.SetReadDeadline(time.Now().Add(90 * time.Second))
		if err != nil {
			return
		}
		if mt == websocket.BinaryMessage {
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

// handleSharedVideo attaches one browser viewer to the camera's video hub.
func (r *relay) handleSharedVideo(w http.ResponseWriter, req *http.Request, cam *camera, headers map[string]string) {
	h, fresh := cam.getOrCreateVideo(r, headers)
	if fresh {
		go r.videoUpstream(cam, h, headers)
	}

	c := &videoClient{
		headers: make(chan responseMsg, 1),
		frames:  make(chan []byte, 2),
	}
	h.add(c)
	defer func() {
		if h.remove(c) {
			h.finish()
			cam.clearVideo(h)
		}
	}()

	var first responseMsg
	select {
	case first = <-c.headers:
	case <-h.done:
		http.Error(w, "camera offline", http.StatusServiceUnavailable)
		return
	case <-time.After(15 * time.Second):
		http.Error(w, "no frames from camera", http.StatusGatewayTimeout)
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

	deadline := time.NewTimer(120 * time.Second)
	defer deadline.Stop()
	for {
		select {
		case frame := <-c.frames:
			if _, err := w.Write(frame); err != nil {
				return
			}
			if flusher != nil {
				flusher.Flush()
			}
		case <-h.done:
			// The upstream can finish just after publishing its last frames;
			// flush anything already queued before closing the viewer.
			for {
				select {
				case frame := <-c.frames:
					_, _ = w.Write(frame)
					if flusher != nil {
						flusher.Flush()
					}
				default:
					return
				}
			}
		case <-deadline.C:
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
