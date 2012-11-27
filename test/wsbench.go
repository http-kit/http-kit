package main

import (
	"code.google.com/p/go.net/websocket"
	"flag"
	"syscall"
	"time"
	// "fmt"
	"log"
	"math/rand"
)

const (
	origin = "http://localhost:9898/"
	wsurl  = "ws://localhost:9898/ws"
)

var concurrency = flag.Int("c", 100, "concurrency")
var total = flag.Int("n", 4000000, "total request")

func main() {
	log.SetFlags(log.LstdFlags | log.Lshortfile)

	chTotal := make(chan int, 10)
	chConcurrency := make(chan int, *concurrency)

	start := time.Now()

	go func() {
		t := 0
		for v := range chTotal {
			t += v
			if t > *total {
				d := time.Since(start).Seconds()
				log.Printf("exit, total %d, per seconds %v", *total, float64(*total) / d)
				syscall.Exit(0)
			}
		}
	}()

	for i := 0; ; i++ {
		chConcurrency <- i
		go bench(chConcurrency, chTotal)
	}
}

func bench(conc chan int, total chan int) {
	count := rand.Intn(10000) + 2000
	ws, err := websocket.Dial(wsurl, "", origin)
	if err != nil {
		log.Fatal(err)
		return
	}

	for i := 0; i < count; i++ {
		c := rand.Intn(20) + 20
		bytes := make([]byte, c)
		// bytes[0] = byte(c + 1)
		for j := 0; j < c; j++ {
			bytes[j] = 65
		}
		if _, err := ws.Write(bytes); err != nil {
			log.Fatal(err)
		}

		msg := make([]byte, 128)
		if n, err := ws.Read(msg); err != nil {
			log.Fatal(err)
		} else if n != c {
			log.Printf("expected length %d, actual %d", c, n)
		} else {
			// log.Printf("received %d", n)
		}
	}
	<-conc         //  allow others to run
	total <- count // let other gorutine compute total
	log.Printf("finished %d\n", count)
}
