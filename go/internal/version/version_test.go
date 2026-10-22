package version

import "testing"

func TestModulePath(t *testing.T) {
	const want = "github.com/Kavi-Sarna/streaming-vector-rag-lakehouse/go"
	if ModulePath != want {
		t.Fatalf("ModulePath = %q, want %q", ModulePath, want)
	}
}
