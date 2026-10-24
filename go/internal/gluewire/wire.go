// Package gluewire reads and writes the AWS Glue Schema Registry frame.
//
// A cloud record is an 18-byte header followed by the Protobuf payload.
// Local Redpanda records stay raw Protobuf and do not use this frame.
//
//	byte 0       header version, always 0x03
//	byte 1       compression, 0x00 none or 0x05 zlib
//	bytes 2-17   schema version id, 16 bytes, big-endian UUID
//	bytes 18-end payload
package gluewire

import (
	"bytes"
	"compress/zlib"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"strings"
)

const (
	headerVersion     byte = 0x03
	compressionNone   byte = 0x00
	compressionZlib   byte = 0x05
	headerLen              = 18
	schemaVersionSize      = 16
)

var (
	// ErrTooShort means the frame does not contain the 18-byte header.
	ErrTooShort = errors.New("glue wire frame shorter than 18 bytes")
	// ErrHeaderVersion means byte 0 is not the Glue header version.
	ErrHeaderVersion = errors.New("glue wire header version is not 0x03")
	// ErrCompression means byte 1 is neither none nor zlib.
	ErrCompression = errors.New("glue wire compression byte is not 0x00 or 0x05")
)

// Encode wraps a Protobuf payload in an uncompressed Glue frame.
func Encode(schemaVersionID [schemaVersionSize]byte, payload []byte) []byte {
	frame := make([]byte, headerLen+len(payload))
	frame[0] = headerVersion
	frame[1] = compressionNone
	copy(frame[2:headerLen], schemaVersionID[:])
	copy(frame[headerLen:], payload)
	return frame
}

// Decode returns the schema version id and the Protobuf payload.
// A zlib compression byte is inflated. Any other compression byte is rejected.
func Decode(frame []byte) ([schemaVersionSize]byte, []byte, error) {
	var id [schemaVersionSize]byte
	if len(frame) < headerLen {
		return id, nil, ErrTooShort
	}
	if frame[0] != headerVersion {
		return id, nil, ErrHeaderVersion
	}
	copy(id[:], frame[2:headerLen])
	payload := frame[headerLen:]
	switch frame[1] {
	case compressionNone:
		out := make([]byte, len(payload))
		copy(out, payload)
		return id, out, nil
	case compressionZlib:
		plain, err := inflate(payload)
		if err != nil {
			return id, nil, err
		}
		return id, plain, nil
	default:
		return id, nil, ErrCompression
	}
}

// ParseSchemaVersionID parses a canonical UUID, 8-4-4-4-12 hex digits.
func ParseSchemaVersionID(s string) ([schemaVersionSize]byte, error) {
	var id [schemaVersionSize]byte
	raw := strings.ReplaceAll(s, "-", "")
	if len(raw) != schemaVersionSize*2 {
		return id, fmt.Errorf("schema version id %q is not a UUID", s)
	}
	decoded, err := hex.DecodeString(raw)
	if err != nil {
		return id, fmt.Errorf("schema version id %q is not a UUID", s)
	}
	copy(id[:], decoded)
	return id, nil
}

func inflate(payload []byte) ([]byte, error) {
	r, err := zlib.NewReader(bytes.NewReader(payload))
	if err != nil {
		return nil, fmt.Errorf("glue wire zlib header: %w", err)
	}
	defer func() { _ = r.Close() }()
	plain, err := io.ReadAll(r)
	if err != nil {
		return nil, fmt.Errorf("glue wire zlib payload: %w", err)
	}
	return plain, nil
}
