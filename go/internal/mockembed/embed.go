// Package mockembed is the local stand-in for Titan embeddings.
//
// Embed returns a deterministic unit vector of 384 dimensions. The same text
// always produces the same vector. It is not a semantic model.
package mockembed

import (
	"crypto/sha256"
	"encoding/binary"
	"math"
)

// Dimensions is the width of a local mock vector.
const Dimensions = 384

// Embed hashes text into a normalized 384-dimension vector.
func Embed(text string) []float32 {
	base := sha256.Sum256([]byte(text))
	out := make([]float32, Dimensions)
	buf := make([]byte, 0, len(base)+2)
	var sum float64
	for i := range out {
		buf = append(buf[:0], base[:]...)
		buf = binary.BigEndian.AppendUint16(buf, uint16(i))
		block := sha256.Sum256(buf)
		u := binary.BigEndian.Uint32(block[:4])
		value := float64(u)/float64(math.MaxUint32)*2 - 1
		out[i] = float32(value)
		sum += value * value
	}
	norm := math.Sqrt(sum)
	if norm == 0 {
		return out
	}
	for i := range out {
		out[i] = float32(float64(out[i]) / norm)
	}
	return out
}
