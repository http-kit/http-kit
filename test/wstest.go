package main

import (
	"code.google.com/p/go.net/websocket"
	"fmt"
	"log"
)

func main() {
	log.SetFlags(log.LstdFlags | log.Lshortfile)

	origin := "http://localhost:9899/"
	url := "ws://localhost:9899/ws"
	ws, err := websocket.Dial(url, "", origin)

	if err != nil {
		log.Fatal(err)
	}

	// fmt.Println(ws)

	if _, err := ws.Write([]byte("\"hello, world!\"\n")); err != nil {
		log.Fatal(err)
	}

	var msg = make([]byte, 512)
	var n int
	if n, err = ws.Read(msg); err != nil {
		log.Fatal(err)
	}
	fmt.Printf("Received: %s.\n", msg[:n])

	ws.PayloadType = websocket.PingFrame
	if n, err := ws.Write([]byte("1")); err != nil {
		log.Fatal(err)
	} else {
		fmt.Println("write", n)
	}
	if n, err = ws.Read(msg); err != nil {
		// log.Fatal(err) not implemented expected
	}
	fmt.Printf("Received: %s.\n", msg[:n])
	ws.Close()
}

// "/api/users/collections/{{id}}/sort"

// old_idx
// new_idx
