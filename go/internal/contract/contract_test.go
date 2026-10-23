package contract_test

import (
	"testing"

	v1 "github.com/Kavi-Sarna/streaming-vector-rag-lakehouse/go/gen/v1"
	"google.golang.org/protobuf/proto"
)

func TestDocumentEventRoundTrip(t *testing.T) {
	in := &v1.DocumentEvent{
		TenantId:   "tenant-alpha",
		DocumentId: "kep-1",
		Version:    3,
		Operation:  v1.Operation_OPERATION_UPDATE,
		Markdown:   "# Deploy\n\nRoll the service forward.\n",
	}

	raw, err := proto.Marshal(in)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}

	var out v1.DocumentEvent
	if err := proto.Unmarshal(raw, &out); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if !proto.Equal(in, &out) {
		t.Fatalf("round trip changed the event: got %+v", &out)
	}
}

func TestStreamQueryMethod(t *testing.T) {
	const want = "/v1.RAGService/StreamQuery"
	if got := v1.RAGService_StreamQuery_FullMethodName; got != want {
		t.Fatalf("method = %q, want %q", got, want)
	}
}
