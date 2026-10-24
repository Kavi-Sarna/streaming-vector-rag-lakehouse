package producer_test

import (
	"math/rand/v2"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	v1 "github.com/Kavi-Sarna/streaming-vector-rag-lakehouse/go/gen/v1"
	"github.com/Kavi-Sarna/streaming-vector-rag-lakehouse/go/internal/producer"
)

func TestLoadDocsReadsFrontMatterTenant(t *testing.T) {
	dir := t.TempDir()
	body := "---\ntitle: \"Deploy\"\ntenant_id: \"tenant-gamma\"\n---\n\n# Deploy\n\nSteps.\n"
	if err := os.WriteFile(filepath.Join(dir, "deploy.md"), []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
	docs, err := producer.LoadDocs(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(docs) != 1 {
		t.Fatalf("docs = %d", len(docs))
	}
	if docs[0].ID != "deploy" || docs[0].Tenant != "tenant-gamma" {
		t.Fatalf("doc = %+v", docs[0])
	}
	if docs[0].Markdown != body {
		t.Fatal("markdown was rewritten")
	}
}

func TestLoadDocsReadsSubdirectories(t *testing.T) {
	dir := t.TempDir()
	for _, rel := range []string{"keps/a.md", "arxiv/b.md", "hf/c.md"} {
		path := filepath.Join(dir, filepath.FromSlash(rel))
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte("# doc\n"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(dir, "arxiv", "feed.xml"), []byte("<feed/>"), 0o644); err != nil {
		t.Fatal(err)
	}
	docs, err := producer.LoadDocs(dir)
	if err != nil {
		t.Fatal(err)
	}
	got := make([]string, len(docs))
	for i, doc := range docs {
		got[i] = doc.ID
	}
	want := []string{"arxiv/b", "hf/c", "keps/a"}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("ids = %v", got)
	}
}

func TestPlanReplaysSameVersion(t *testing.T) {
	docs := []producer.Doc{{ID: "only", Markdown: "body"}}
	events, err := producer.Plan(docs, []string{"tenant-alpha"}, producer.PlanConfig{
		Burst:           4,
		DuplicateChance: 1,
		Rand:            rand.New(rand.NewPCG(1, 2)),
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(events) != 4 {
		t.Fatalf("len = %d", len(events))
	}
	if events[0] != events[1] || events[0].Version != 1 || events[0].Operation != v1.Operation_OPERATION_INSERT {
		t.Fatalf("replayed insert = %+v %+v", events[0], events[1])
	}
	if events[2] != events[3] || events[2].Version != 2 || events[2].Operation != v1.Operation_OPERATION_UPDATE {
		t.Fatalf("replayed update = %+v %+v", events[2], events[3])
	}
}

func TestPlanInsertUpdateDelete(t *testing.T) {
	docs := make([]producer.Doc, 5)
	for i := range docs {
		docs[i] = producer.Doc{ID: string(rune('a' + i)), Markdown: "body"}
	}
	events, err := producer.Plan(docs, []string{"tenant-alpha", "tenant-beta"}, producer.PlanConfig{
		Burst:        15,
		DeleteChance: 1,
		Rand:         rand.New(rand.NewPCG(1, 2)),
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(events) != 15 {
		t.Fatalf("len = %d", len(events))
	}
	if events[0].TenantID != "tenant-alpha" || events[3].TenantID != "tenant-beta" {
		t.Fatalf("tenants = %s %s", events[0].TenantID, events[3].TenantID)
	}
	for i, id := range []string{"a", "b", "c", "d", "e"} {
		base := i * 3
		if events[base].DocumentID != id || events[base].Operation != v1.Operation_OPERATION_INSERT || events[base].Version != 1 {
			t.Fatalf("insert = %+v", events[base])
		}
		if events[base+1].Operation != v1.Operation_OPERATION_UPDATE || events[base+1].Version != 2 {
			t.Fatalf("update = %+v", events[base+1])
		}
		if events[base+2].Operation != v1.Operation_OPERATION_DELETE || events[base+2].Version != 3 || events[base+2].Markdown != "" {
			t.Fatalf("delete = %+v", events[base+2])
		}
	}
}

func TestPlanRandomDeletes(t *testing.T) {
	docs := make([]producer.Doc, 8)
	for i := range docs {
		docs[i] = producer.Doc{ID: string(rune('a' + i)), Markdown: "body"}
	}
	first := planOps(t, docs, 1)
	again := planOps(t, docs, 1)
	if first != again {
		t.Fatalf("seed 1 changed: %s vs %s", first, again)
	}
	other := planOps(t, docs, 2)
	if first == other {
		t.Fatal("two seeds produced the same operations")
	}
	if !containsDelete(first) || !containsDelete(other) {
		t.Fatalf("expected a delete in each run: %s | %s", first, other)
	}
}

func TestPlanExtendsWithUpdates(t *testing.T) {
	docs := []producer.Doc{{ID: "only", Markdown: "body", Tenant: "tenant-alpha"}}
	events, err := producer.Plan(docs, []string{"tenant-beta"}, producer.PlanConfig{Burst: 4})
	if err != nil {
		t.Fatal(err)
	}
	if events[3].Operation != v1.Operation_OPERATION_UPDATE || events[3].Version != 4 {
		t.Fatalf("last = %+v", events[3])
	}
	if events[3].TenantID != "tenant-alpha" {
		t.Fatalf("tenant = %s", events[3].TenantID)
	}
	for _, event := range events {
		if event.Operation == v1.Operation_OPERATION_DELETE {
			t.Fatalf("delete with chance 0: %+v", event)
		}
	}
}

func TestSleepDurationStaysInRange(t *testing.T) {
	rng := rand.New(rand.NewPCG(1, 2))
	min := 200 * time.Millisecond
	max := 1500 * time.Millisecond
	for range 200 {
		delay := producer.SleepDuration(min, max, rng)
		if delay < min || delay > max {
			t.Fatalf("delay %s outside %s..%s", delay, min, max)
		}
	}
	if producer.SleepDuration(time.Second, time.Second, rng) != time.Second {
		t.Fatal("equal bounds did not return that duration")
	}
}

func planOps(t *testing.T, docs []producer.Doc, seed uint64) string {
	t.Helper()
	events, err := producer.Plan(docs, []string{"tenant-alpha"}, producer.PlanConfig{
		Burst:        24,
		DeleteChance: 0.5,
		Rand:         rand.New(rand.NewPCG(seed, seed+1)),
	})
	if err != nil {
		t.Fatal(err)
	}
	ops := make([]byte, len(events))
	for i, event := range events {
		switch event.Operation {
		case v1.Operation_OPERATION_INSERT:
			ops[i] = 'I'
		case v1.Operation_OPERATION_UPDATE:
			ops[i] = 'U'
		case v1.Operation_OPERATION_DELETE:
			ops[i] = 'D'
			if event.Markdown != "" {
				t.Fatalf("delete markdown = %q", event.Markdown)
			}
		default:
			t.Fatalf("op %s", event.Operation)
		}
	}
	return string(ops)
}

func containsDelete(ops string) bool {
	for _, op := range ops {
		if op == 'D' {
			return true
		}
	}
	return false
}
