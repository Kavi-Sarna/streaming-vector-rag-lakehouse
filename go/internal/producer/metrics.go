package producer

import (
	"errors"
	"fmt"
	"net"
	"net/http"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// Metrics counts published events by operation.
type Metrics struct {
	events *prometheus.CounterVec
}

// NewMetrics registers rag_producer_events_total on reg.
func NewMetrics(reg prometheus.Registerer) (*Metrics, error) {
	events := prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "rag_producer_events_total",
		Help: "Document events published, by operation.",
	}, []string{"operation"})
	if err := reg.Register(events); err != nil {
		return nil, fmt.Errorf("register producer metrics: %w", err)
	}
	return &Metrics{events: events}, nil
}

// Event records one successful publish. A nil Metrics does nothing.
func (m *Metrics) Event(operation string) {
	if m == nil {
		return
	}
	m.events.WithLabelValues(operation).Inc()
}

// ListenAndServeMetrics serves Prometheus text on addr. The server is closed with Shutdown.
func ListenAndServeMetrics(addr string, gatherer prometheus.Gatherer) (*http.Server, error) {
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, fmt.Errorf("metrics listen %s: %w", addr, err)
	}
	server := &http.Server{
		Addr:    listener.Addr().String(),
		Handler: promhttp.HandlerFor(gatherer, promhttp.HandlerOpts{}),
	}
	go func() {
		err := server.Serve(listener)
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			fmt.Printf("metrics server: %v\n", err)
		}
	}()
	return server, nil
}
