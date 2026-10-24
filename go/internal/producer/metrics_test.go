package producer_test

import (
	"context"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/testutil"

	"github.com/Kavi-Sarna/streaming-vector-rag-lakehouse/go/internal/producer"
)

func TestMetricsCountOperations(t *testing.T) {
	reg := prometheus.NewRegistry()
	metrics, err := producer.NewMetrics(reg)
	if err != nil {
		t.Fatal(err)
	}
	metrics.Event("INSERT")
	metrics.Event("INSERT")
	metrics.Event("UPDATE")
	metrics.Event("DELETE")

	want := `
# HELP rag_producer_events_total Document events published, by operation.
# TYPE rag_producer_events_total counter
rag_producer_events_total{operation="DELETE"} 1
rag_producer_events_total{operation="INSERT"} 2
rag_producer_events_total{operation="UPDATE"} 1
`
	if err := testutil.GatherAndCompare(reg, strings.NewReader(want), "rag_producer_events_total"); err != nil {
		t.Fatal(err)
	}
}

func TestMetricsEndpoint(t *testing.T) {
	reg := prometheus.NewRegistry()
	metrics, err := producer.NewMetrics(reg)
	if err != nil {
		t.Fatal(err)
	}
	metrics.Event("UPDATE")
	server, err := producer.ListenAndServeMetrics("127.0.0.1:0", reg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		_ = server.Shutdown(ctx)
	})

	response, err := http.Get("http://" + server.Addr + "/metrics")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = response.Body.Close() }()
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("status %d", response.StatusCode)
	}
	if !strings.Contains(string(body), `rag_producer_events_total{operation="UPDATE"} 1`) {
		t.Fatalf("body = %s", body)
	}
}
