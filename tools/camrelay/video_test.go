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
