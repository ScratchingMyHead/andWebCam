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

func TestProxyRoundTrip(t *testing.T) {
	const token = "test-token"
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
	if err := control.WriteJSON(helloMsg{Type: "hello", ID: "cam2", Version: 1}); err != nil {
		t.Fatal(err)
	}
	var ok ackMsg
	if err := control.ReadJSON(&ok); err != nil || ok.Type != "hello_ok" {
		t.Fatalf("hello: %v %+v", err, ok)
	}

	type result struct {
		body string
		err  error
		code int
	}
	out := make(chan result, 1)
	go func() {
		req, _ := http.NewRequest("GET", srv.URL+"/api/status", nil)
		req.Host = "cam2.example.com"
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			out <- result{err: err}
			return
		}
		defer resp.Body.Close()
		b, _ := io.ReadAll(resp.Body)
		out <- result{body: string(b), code: resp.StatusCode}
	}()

	var open openMsg
	if err := control.ReadJSON(&open); err != nil {
		t.Fatal(err)
	}
	if open.Type != "open" || open.Method != "GET" || open.Path != "/api/status" {
		t.Fatalf("bad open: %+v", open)
	}
	reqWS, _, err := websocket.DefaultDialer.Dial(
		wsURL+"/req/"+strconv.FormatInt(open.ID, 10)+"?cam=cam2", hdr)
	if err != nil {
		t.Fatal(err)
	}
	defer reqWS.Close()
	// GET has no body; the relay sends an immediate end marker.
	for {
		mt, data, err := reqWS.ReadMessage()
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
	if err := reqWS.WriteJSON(responseMsg{
		Type:    "resp",
		Status:  200,
		Headers: map[string]string{"Content-Type": "text/plain"},
	}); err != nil {
		t.Fatal(err)
	}
	if err := reqWS.WriteMessage(websocket.BinaryMessage, []byte("all good")); err != nil {
		t.Fatal(err)
	}
	if err := reqWS.WriteMessage(websocket.TextMessage, []byte(`{"type":"end"}`)); err != nil {
		t.Fatal(err)
	}

	select {
	case res := <-out:
		if res.err != nil {
			t.Fatal(res.err)
		}
		if res.code != 200 || res.body != "all good" {
			t.Fatalf("got %d %q", res.code, res.body)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("proxy timed out")
	}
}
