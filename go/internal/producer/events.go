package producer

import (
	"fmt"
	"math/rand/v2"

	v1 "github.com/Kavi-Sarna/streaming-vector-rag-lakehouse/go/gen/v1"
)

// Event is one DocumentEvent before it is marshaled.
type Event struct {
	TenantID   string
	DocumentID string
	Version    int64
	Operation  v1.Operation
	Markdown   string
}

// PlanConfig chooses how many events to build, which documents are deleted,
// and how often an event is published again with the same version.
type PlanConfig struct {
	Burst           int
	DeleteChance    float64
	DuplicateChance float64
	Rand            *rand.Rand
}

// Plan builds burst CDC events. Each document is inserted, then updated.
// After that update, the document is deleted with DeleteChance.
// Further events keep updating a live document, or insert it again after a delete.
func Plan(docs []Doc, tenants []string, cfg PlanConfig) ([]Event, error) {
	if len(docs) == 0 {
		return nil, fmt.Errorf("no documents")
	}
	if len(tenants) == 0 {
		return nil, fmt.Errorf("no tenants")
	}
	if cfg.Burst < 1 {
		return nil, fmt.Errorf("burst must be at least 1")
	}
	if cfg.DeleteChance < 0 || cfg.DeleteChance > 1 {
		return nil, fmt.Errorf("delete chance must be from 0 to 1")
	}
	if cfg.DuplicateChance < 0 || cfg.DuplicateChance > 1 {
		return nil, fmt.Errorf("duplicate chance must be from 0 to 1")
	}
	if (cfg.DeleteChance > 0 || cfg.DuplicateChance > 0) && cfg.Rand == nil {
		return nil, fmt.Errorf("rand is required when delete chance or duplicate chance is set")
	}

	versions := make([]int64, len(docs))
	alive := make([]bool, len(docs))
	events := make([]Event, 0, cfg.Burst)
	appendEvent := func(index int, op v1.Operation, markdown string) {
		versions[index]++
		doc := docs[index]
		tenant := doc.Tenant
		if tenant == "" {
			tenant = tenants[index%len(tenants)]
		}
		events = append(events, Event{
			TenantID:   tenant,
			DocumentID: doc.ID,
			Version:    versions[index],
			Operation:  op,
			Markdown:   markdown,
		})
		if len(events) < cfg.Burst && duplicateNow(cfg) {
			events = append(events, events[len(events)-1])
		}
	}

	for index, doc := range docs {
		if len(events) == cfg.Burst {
			return events, nil
		}
		appendEvent(index, v1.Operation_OPERATION_INSERT, doc.Markdown)
		alive[index] = true
		if len(events) == cfg.Burst {
			return events, nil
		}
		appendEvent(index, v1.Operation_OPERATION_UPDATE, doc.Markdown+"\n\n<!-- cdc update -->\n")
		if len(events) == cfg.Burst {
			return events, nil
		}
		if deleteNow(cfg) {
			appendEvent(index, v1.Operation_OPERATION_DELETE, "")
			alive[index] = false
		}
	}
	for len(events) < cfg.Burst {
		index := len(events) % len(docs)
		doc := docs[index]
		if !alive[index] {
			appendEvent(index, v1.Operation_OPERATION_INSERT, doc.Markdown)
			alive[index] = true
			continue
		}
		if deleteNow(cfg) {
			appendEvent(index, v1.Operation_OPERATION_DELETE, "")
			alive[index] = false
			continue
		}
		note := fmt.Sprintf("\n\n<!-- cdc update %d -->\n", versions[index]+1)
		appendEvent(index, v1.Operation_OPERATION_UPDATE, doc.Markdown+note)
	}
	return events, nil
}

func deleteNow(cfg PlanConfig) bool {
	return cfg.DeleteChance > 0 && cfg.Rand.Float64() < cfg.DeleteChance
}

func duplicateNow(cfg PlanConfig) bool {
	return cfg.DuplicateChance > 0 && cfg.Rand.Float64() < cfg.DuplicateChance
}
