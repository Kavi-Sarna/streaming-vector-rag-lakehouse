// Command producer publishes DocumentEvent CDC records from a markdown corpus.
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"math/rand/v2"
	"os"
	"os/signal"
	"strings"
	"time"

	"github.com/prometheus/client_golang/prometheus"

	"github.com/Kavi-Sarna/streaming-vector-rag-lakehouse/go/internal/producer"
)

func main() {
	brokers := flag.String("brokers", "localhost:9092", "comma-separated Kafka brokers")
	topic := flag.String("topic", "document.events.v1", "Kafka topic")
	input := flag.String("input", "testdata", "directory of markdown files, including subdirectories")
	rate := flag.Int("rate", 50, "events per second when --sleep-max is 0")
	sleepMin := flag.Duration("sleep-min", 200*time.Millisecond, "minimum pause between events")
	sleepMax := flag.Duration("sleep-max", 1500*time.Millisecond, "maximum pause between events; 0 uses --rate")
	burst := flag.Int("burst", 300, "number of events to publish")
	deleteChance := flag.Float64("delete-chance", 0.2, "chance a document is deleted after it is updated")
	seed := flag.Int64("seed", 0, "random seed; 0 uses the current time")
	tenants := flag.String("tenants", "tenant-alpha,tenant-beta", "comma-separated tenant ids")
	security := flag.String("security", "plaintext", "plaintext for Redpanda, or aws-msk-iam for MSK Serverless")
	glueSchemaVersion := flag.String("glue-schema-version", "", "Glue schema version UUID; empty sends raw Protobuf")
	metricsAddr := flag.String("metrics-addr", ":9095", "Prometheus listen address; empty disables metrics")
	flag.Parse()

	docs, err := producer.LoadDocs(*input)
	if err != nil {
		log.Fatal(err)
	}
	chosen := *seed
	if chosen == 0 {
		chosen = time.Now().UnixNano()
	}
	rng := rand.New(rand.NewPCG(uint64(chosen), uint64(chosen)^0x9e3779b97f4a7c15))
	fmt.Printf("seed %d\n", chosen)
	events, err := producer.Plan(docs, producer.SplitList(*tenants), producer.PlanConfig{
		Burst:        *burst,
		DeleteChance: *deleteChance,
		Rand:         rng,
	})
	if err != nil {
		log.Fatal(err)
	}

	var metrics *producer.Metrics
	if *metricsAddr != "" {
		metrics, err = producer.NewMetrics(prometheus.DefaultRegisterer)
		if err != nil {
			log.Fatal(err)
		}
		server, err := producer.ListenAndServeMetrics(*metricsAddr, prometheus.DefaultGatherer)
		if err != nil {
			log.Fatal(err)
		}
		defer func() { _ = server.Close() }()
		fmt.Printf("metrics %s\n", metricsURL(*metricsAddr))
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	err = producer.Publish(ctx, producer.PublishConfig{
		Brokers:           producer.SplitList(*brokers),
		Topic:             *topic,
		Security:          *security,
		GlueSchemaVersion: *glueSchemaVersion,
		Rate:              *rate,
		SleepMin:          *sleepMin,
		SleepMax:          *sleepMax,
		Rand:              rng,
		Metrics:           metrics,
		Events:            events,
	})
	if err != nil {
		log.Fatal(err)
	}
	fmt.Printf("published %d events to %s\n", len(events), *topic)
	if *metricsAddr != "" {
		fmt.Printf("dashboard http://localhost:3000\n")
		fmt.Printf("metrics stay up until interrupt\n")
		<-ctx.Done()
	}
}

func metricsURL(addr string) string {
	if strings.HasPrefix(addr, ":") {
		return "http://localhost" + addr + "/metrics"
	}
	return "http://" + addr + "/metrics"
}
