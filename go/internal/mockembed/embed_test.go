package mockembed_test

import (
	"bytes"
	"encoding/json"
	"math"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/Kavi-Sarna/streaming-vector-rag-lakehouse/go/internal/mockembed"
)

func TestEmbedShape(t *testing.T) {
	vector := mockembed.Embed("how does deployment work")
	if len(vector) != mockembed.Dimensions {
		t.Fatalf("len = %d, want %d", len(vector), mockembed.Dimensions)
	}
	var sum float64
	for _, value := range vector {
		if math.IsNaN(float64(value)) || math.IsInf(float64(value), 0) {
			t.Fatalf("vector contains a non-finite value")
		}
		sum += float64(value) * float64(value)
	}
	if math.Abs(math.Sqrt(sum)-1) > 1e-5 {
		t.Fatalf("norm = %v, want 1", math.Sqrt(sum))
	}
}

func TestEmbedIsDeterministic(t *testing.T) {
	left := mockembed.Embed("tenant-alpha")
	right := mockembed.Embed("tenant-alpha")
	other := mockembed.Embed("tenant-beta")
	if !equal(left, right) {
		t.Fatal("same text produced different vectors")
	}
	if equal(left, other) {
		t.Fatal("different text produced the same vector")
	}
}

func TestEmbedHTTP(t *testing.T) {
	server := httptest.NewServer(mockembed.Handler())
	t.Cleanup(server.Close)

	response, err := http.Post(server.URL+"/embed", "application/json", bytes.NewBufferString(`{"text":"hello"}`))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = response.Body.Close() }()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("status = %d", response.StatusCode)
	}
	var body struct {
		Dimensions int       `json:"dimensions"`
		Vector     []float32 `json:"vector"`
	}
	if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body.Dimensions != mockembed.Dimensions || len(body.Vector) != mockembed.Dimensions {
		t.Fatalf("dimensions = %d, vector len = %d", body.Dimensions, len(body.Vector))
	}
}

func TestEmbedRejectsInvalidJSON(t *testing.T) {
	server := httptest.NewServer(mockembed.Handler())
	t.Cleanup(server.Close)

	response, err := http.Post(server.URL+"/embed", "application/json", bytes.NewBufferString(`{`))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = response.Body.Close() }()
	if response.StatusCode != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", response.StatusCode)
	}
}

func equal(left, right []float32) bool {
	if len(left) != len(right) {
		return false
	}
	for i := range left {
		if left[i] != right[i] {
			return false
		}
	}
	return true
}
