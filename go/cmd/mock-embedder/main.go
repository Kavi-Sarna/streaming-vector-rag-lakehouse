// Command mock-embedder serves the local 384-dimension embedding HTTP API.
package main

import (
	"log"
	"net/http"
	"os"
	"time"

	"github.com/Kavi-Sarna/streaming-vector-rag-lakehouse/go/internal/mockembed"
)

func main() {
	if len(os.Args) == 2 && os.Args[1] == "-healthcheck" {
		os.Exit(healthcheck())
	}

	addr := ":8080"
	if value := os.Getenv("LISTEN_ADDR"); value != "" {
		addr = value
	}
	server := &http.Server{
		Addr:              addr,
		Handler:           mockembed.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
	}
	log.Printf("mock embedder listening on %s", addr)
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatal(err)
	}
}

func healthcheck() int {
	response, err := http.Get("http://127.0.0.1:8080/health")
	if err != nil {
		log.Print(err)
		return 1
	}
	defer func() { _ = response.Body.Close() }()
	if response.StatusCode != http.StatusOK {
		log.Printf("health status %d", response.StatusCode)
		return 1
	}
	return 0
}
