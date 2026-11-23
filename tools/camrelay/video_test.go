package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// Sound and picture must not share a hub: they are different streams with
// different producers on the phone. The relay needs one upstream per path.
func TestAudioAndVideoUseSeparateUpstreams(t *testing.T) {
	const token = "paths-token"
	r := newRelay(token)
	srv := httptest.NewServer(r)
	defer srv.Close()
	wsURL := "ws" + strings.TrimPrefix(srv.URL, "http")
	hdr := http.Header{"Authorization": []string{"Bearer " + token}}

	control, _, err := websocket.DefaultDialer.Dial(wsURL+"/ws/", hdr)
	if err != nil {
		t.Fatal(err)
	}
	defer control.Close()
	_ = control.WriteJSON(helloMsg{Type: "hello", ID: "cam2", Version: 1})
	var ok ackMsg
	if err := control.ReadJSON(&ok); err != nil || ok.Type != "hello_ok" {
		t.Fatalf("hello: %v %+v", err, ok)
	}

	results := make(chan string, 2)
	for _, path := range []string{"/video", "/audio"} {
		go func(path string) {
			req, _ := http.NewRequest("GET", srv.URL+path+"?src=tok-"+strings.TrimPrefix(path, "/"), nil)
			req.Host = "cam2.offloop.net"
			resp, err := http.DefaultClient.Do(req)
			if err != nil {
				results <- "ERR " + err.Error()
				return
			}
			defer resp.Body.Close()
			b, _ := io.ReadAll(resp.Body)
			results <- path + ":" + string(b)
		}(path)
	}

	_ = control.SetReadDeadline(time.Now().Add(10 * time.Second))
	upstreams := make(map[string]openMsg)
	for range []string{"/video", "/audio"} {
		var open openMsg
		if err := control.ReadJSON(&open); err != nil {
			t.Fatal(err)
		}
		if open.Path != "/video" && open.Path != "/audio" {
			t.Fatalf("unexpected upstream path %q", open.Path)
		}
		// The page's stream token travels in the query; without it the app
		// cannot drop a page's old connection when it reconnects.
		if want := "tok-" + strings.TrimPrefix(open.Path, "/"); !strings.Contains(open.Query, want) {
			t.Fatalf("upstream query %q does not carry %q", open.Query, want)
		}
		upstreams[open.Path] = open
	}
	for _, want := range []string{"/video", "/audio"} {
		open, exists := upstreams[want]
		if !exists {
			t.Fatalf("no upstream opened for %s", want)
		}
		phone, _, err := websocket.DefaultDialer.Dial(
			wsURL+"/req/"+strconv.FormatInt(open.ID, 10)+"?cam=cam2", hdr)
		if err != nil {
			t.Fatal(err)
		}
		defer phone.Close()
		_ = phone.SetReadDeadline(time.Now().Add(5 * time.Second))
		for {
			mt, data, err := phone.ReadMessage()
			if err != nil {
				t.Fatal(err)
			}
			if mt == websocket.TextMessage {
				var end struct{ Type string }
				_ = json.Unmarshal(data, &end)
				if end.Type == "end" {
					break
				}
			}
		}
		_ = phone.WriteJSON(responseMsg{Type: "resp", Status: 200,
			Headers: map[string]string{"Content-Type": "application/octet-stream"}})
		_ = phone.WriteMessage(websocket.BinaryMessage, []byte("CHUNK"+want))
		_ = phone.WriteMessage(websocket.TextMessage, []byte(`{"type":"end"}`))
	}

	for range []string{"/video", "/audio"} {
		select {
		case body := <-results:
			if !strings.Contains(body, "CHUNK") {
				t.Fatalf("viewer did not get its stream: %q", body)
			}
		case <-time.After(5 * time.Second):
			t.Fatal("viewer timed out")
		}
	}
}

func TestVideoFanoutUsesOneUpstream(t *testing.T) {
	const token = "fanout-token"
	r := newRelay(token)
	srv := httptest.NewServer(r)
	defer srv.Close()
	wsURL := "ws" + strings.TrimPrefix(srv.URL, "http")
	hdr := http.Header{"Authorization": []string{"Bearer " + token}}

	control, _, err := websocket.DefaultDialer.Dial(wsURL+"/ws/", hdr)
	if err != nil {
		t.Fatal(err)
	}
	defer control.Close()
	_ = control.WriteJSON(helloMsg{Type: "hello", ID: "cam2", Version: 1})
	var ok ackMsg
	if err := control.ReadJSON(&ok); err != nil || ok.Type != "hello_ok" {
		t.Fatalf("hello: %v %+v", err, ok)
	}

	results := make(chan string, 2)
	for i := 0; i < 2; i++ {
		go func() {
			req, _ := http.NewRequest("GET", srv.URL+"/video", nil)
			req.Host = "cam2.offloop.net"
			resp, err := http.DefaultClient.Do(req)
			if err != nil {
				results <- "ERR " + err.Error()
				return
			}
			defer resp.Body.Close()
			b, _ := io.ReadAll(resp.Body)
			results <- string(b)
		}()
	}

	_ = control.SetReadDeadline(time.Now().Add(5 * time.Second))
	var open openMsg
	if err := control.ReadJSON(&open); err != nil {
		t.Fatal(err)
	}
	if open.Path != "/video" {
		t.Fatalf("unexpected upstream path %q", open.Path)
	}

	phone, _, err := websocket.DefaultDialer.Dial(
		wsURL+"/req/"+strconv.FormatInt(open.ID, 10)+"?cam=cam2", hdr)
	if err != nil {
		t.Fatal(err)
	}
	defer phone.Close()
	_ = phone.SetReadDeadline(time.Now().Add(5 * time.Second))
	for {
		mt, data, err := phone.ReadMessage()
		if err != nil {
			t.Fatal(err)
		}
		if mt == websocket.TextMessage {
			var end struct{ Type string }
			_ = json.Unmarshal(data, &end)
			if end.Type == "end" {
				break
			}
		}
	}
	_ = phone.WriteJSON(responseMsg{
		Type:    "resp",
		Status:  200,
		Headers: map[string]string{"Content-Type": "multipart/x-mixed-replace; boundary=andwebcamframe"},
	})
	_ = phone.WriteMessage(websocket.BinaryMessage, []byte("FRAME"))
	_ = phone.WriteMessage(websocket.TextMessage, []byte(`{"type":"end"}`))

	for i := 0; i < 2; i++ {
		select {
		case body := <-results:
			if !strings.Contains(body, "FRAME") {
				t.Fatalf("viewer did not get the shared frame: %q", body)
			}
		case <-time.After(5 * time.Second):
			t.Fatal("viewer timed out")
		}
	}
}
