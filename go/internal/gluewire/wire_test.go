package gluewire_test

import (
	"bytes"
	"compress/zlib"
	"errors"
	"testing"

	v1 "github.com/Kavi-Sarna/streaming-vector-rag-lakehouse/go/gen/v1"
	"github.com/Kavi-Sarna/streaming-vector-rag-lakehouse/go/internal/gluewire"
	"google.golang.org/protobuf/proto"
)

const schemaVersion = "11111111-2222-3333-4444-555555555555"

func TestEncodeHeader(t *testing.T) {
	id := mustID(t)
	frame := gluewire.Encode(id, []byte{0x0a, 0x01})

	if got, want := frame[0], byte(0x03); got != want {
		t.Fatalf("header version = %#x, want %#x", got, want)
	}
	if got, want := frame[1], byte(0x00); got != want {
		t.Fatalf("compression = %#x, want %#x", got, want)
	}
	if !bytes.Equal(frame[2:18], id[:]) {
		t.Fatalf("schema version id = %x, want %x", frame[2:18], id)
	}
	if !bytes.Equal(frame[18:], []byte{0x0a, 0x01}) {
		t.Fatalf("payload = %x", frame[18:])
	}
}

func TestDocumentEventRoundTrip(t *testing.T) {
	id := mustID(t)
	in := &v1.DocumentEvent{
		TenantId:   "tenant-alpha",
		DocumentId: "kep-1",
		Version:    3,
		Operation:  v1.Operation_OPERATION_UPDATE,
		Markdown:   "# Deploy\n",
	}
	raw, err := proto.Marshal(in)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}

	gotID, payload, err := gluewire.Decode(gluewire.Encode(id, raw))
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if gotID != id {
		t.Fatalf("schema version id = %x, want %x", gotID, id)
	}
	var out v1.DocumentEvent
	if err := proto.Unmarshal(payload, &out); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if !proto.Equal(in, &out) {
		t.Fatalf("round trip changed the event: got %+v", &out)
	}
}

func TestDecodeZlib(t *testing.T) {
	id := mustID(t)
	payload := []byte("markdown")
	var compressed bytes.Buffer
	w := zlib.NewWriter(&compressed)
	if _, err := w.Write(payload); err != nil {
		t.Fatalf("compress: %v", err)
	}
	if err := w.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}

	frame := make([]byte, 18+compressed.Len())
	frame[0] = 0x03
	frame[1] = 0x05
	copy(frame[2:18], id[:])
	copy(frame[18:], compressed.Bytes())

	gotID, got, err := gluewire.Decode(frame)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if gotID != id {
		t.Fatalf("schema version id = %x, want %x", gotID, id)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("payload = %q, want %q", got, payload)
	}
}

func TestDecodeRejectsBadFrames(t *testing.T) {
	id := mustID(t)
	good := gluewire.Encode(id, []byte{1})

	shortFrame := good[:10]
	_, _, err := gluewire.Decode(shortFrame)
	if !errors.Is(err, gluewire.ErrTooShort) {
		t.Fatalf("short frame error = %v", err)
	}

	badVersion := append([]byte(nil), good...)
	badVersion[0] = 0x01
	_, _, err = gluewire.Decode(badVersion)
	if !errors.Is(err, gluewire.ErrHeaderVersion) {
		t.Fatalf("bad version error = %v", err)
	}

	badCompression := append([]byte(nil), good...)
	badCompression[1] = 0x01
	_, _, err = gluewire.Decode(badCompression)
	if !errors.Is(err, gluewire.ErrCompression) {
		t.Fatalf("bad compression error = %v", err)
	}
}

func mustID(t *testing.T) [16]byte {
	t.Helper()
	id, err := gluewire.ParseSchemaVersionID(schemaVersion)
	if err != nil {
		t.Fatalf("parse id: %v", err)
	}
	return id
}
