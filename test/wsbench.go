package main

import (
	// go get code.google.com/p/go.net
	"code.google.com/p/go.net/websocket"
	"flag"
	"log"
	"math/rand"
	"syscall"
	"time"
)

const (
	origin = "http://localhost:9899/"
	wsurl  = "ws://localhost:9899/test"
)

var concurrency = flag.Int("c", 100, "concurrency")
var total = flag.Int("n", 4000000, "total request")
var messageSize = flag.Int("m", 512, "message size per request")

func main() {
	log.SetFlags(log.LstdFlags | log.Lshortfile)
	rand.Seed(time.Now().UnixNano())
	flag.Parse()
	log.Printf("concurrency: %v, total request: %v, message size %v", *concurrency, *total, *messageSize)
	chTotal := make(chan int, 10)
	chConcurrency := make(chan int, *concurrency)
	start := time.Now()

	go func() {
		t := 0
		for v := range chTotal {
			t += v
			if t > *total {
				d := time.Since(start).Seconds()
				rps := float64(*total) / d
				t := rps * 512 / 1024 / 1024
				log.Printf("total %d, per seconds %v, message throughput: %v MB/s", *total, rps, t)
				syscall.Exit(0)
			}
		}
	}()

	for i := 0; ; i++ {
		chConcurrency <- i
		go bench(chConcurrency, chTotal)
	}
}

func bench(concurrency chan int, total chan int) {
	count := rand.Intn(10000) + 1000
	ws, err := websocket.Dial(wsurl, "", origin)
	if err != nil {
		log.Fatal(err)
		return
	}

	for i := 0; i < count; i++ {
		c := *messageSize
		bytes := make([]byte, c)
		for j := 0; j < c; j++ {
			bytes[j] = 65
		}
		if _, err := ws.Write(bytes); err != nil {
			log.Fatal(err)
		}

		msg := make([]byte, 1024)
		if n, err := ws.Read(msg); err != nil {
			log.Fatal(err)
		} else if n != c {
			log.Printf("expected length %d, actual %d", c, n)
		}
	}
	total <- count                                          // let other gorutine compute total
	log.Printf("#%d finished %d requests\n", <-concurrency, count) //  allow others to run
}
