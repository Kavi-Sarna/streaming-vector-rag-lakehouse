package producer

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"math/rand/v2"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/twmb/franz-go/pkg/kadm"
	"github.com/twmb/franz-go/pkg/kerr"
	"github.com/twmb/franz-go/pkg/kgo"
	"github.com/twmb/franz-go/pkg/sasl"
	franzaws "github.com/twmb/franz-go/pkg/sasl/aws"
	"google.golang.org/protobuf/proto"

	v1 "github.com/Kavi-Sarna/streaming-vector-rag-lakehouse/go/gen/v1"
	"github.com/Kavi-Sarna/streaming-vector-rag-lakehouse/go/internal/gluewire"
)

// PublishConfig is how one run connects and paces records.
type PublishConfig struct {
	Brokers           []string
	Topic             string
	Security          string
	GlueSchemaVersion string
	Rate              int
	SleepMin          time.Duration
	SleepMax          time.Duration
	Rand              *rand.Rand
	Metrics           *Metrics
	Events            []Event
}

// Publish writes events to Kafka. An empty Glue schema version sends raw Protobuf.
// A positive SleepMax pauses a random duration between events. SleepMax 0 uses Rate.
func Publish(ctx context.Context, cfg PublishConfig) error {
	if cfg.SleepMin < 0 || (cfg.SleepMax > 0 && cfg.SleepMax < cfg.SleepMin) {
		return fmt.Errorf("sleep-min must be from 0 through sleep-max")
	}
	if cfg.SleepMax > 0 && cfg.Rand == nil {
		return fmt.Errorf("rand is required when sleep-max is set")
	}
	if cfg.SleepMax == 0 && cfg.Rate < 0 {
		return fmt.Errorf("rate must be at least 0")
	}

	var schemaID [16]byte
	useGlue := cfg.GlueSchemaVersion != ""
	if useGlue {
		id, err := gluewire.ParseSchemaVersionID(cfg.GlueSchemaVersion)
		if err != nil {
			return err
		}
		schemaID = id
	}

	opts := []kgo.Opt{
		kgo.SeedBrokers(cfg.Brokers...),
		kgo.DefaultProduceTopic(cfg.Topic),
		kgo.RequiredAcks(kgo.AllISRAcks()),
	}
	if cfg.Security == "aws-msk-iam" {
		mech, err := mskIAM()
		if err != nil {
			return err
		}
		opts = append(opts, kgo.SASL(mech), kgo.DialTLSConfig(&tls.Config{MinVersion: tls.VersionTLS12}))
	} else if cfg.Security != "plaintext" {
		return fmt.Errorf("security %q is not plaintext or aws-msk-iam", cfg.Security)
	}

	client, err := kgo.NewClient(opts...)
	if err != nil {
		return fmt.Errorf("kafka client: %w", err)
	}
	defer client.Close()

	if err := ensureTopic(ctx, client, cfg.Topic); err != nil {
		return err
	}

	var pace *time.Ticker
	if cfg.SleepMax == 0 && cfg.Rate > 0 {
		pace = time.NewTicker(time.Second / time.Duration(cfg.Rate))
		defer pace.Stop()
	}

	for i, event := range cfg.Events {
		if i > 0 {
			if err := waitBeforeEvent(ctx, cfg, pace); err != nil {
				return err
			}
		}
		payload, err := encode(event, useGlue, schemaID)
		if err != nil {
			return err
		}
		result := client.ProduceSync(ctx, &kgo.Record{
			Key:   []byte(event.DocumentID),
			Value: payload,
		})
		if err := result.FirstErr(); err != nil {
			return fmt.Errorf("produce %s v%d: %w", event.DocumentID, event.Version, err)
		}
		operation := operationName(event.Operation)
		cfg.Metrics.Event(operation)
		fmt.Printf("%s %s v%d %s\n", event.TenantID, event.DocumentID, event.Version, operation)
	}
	return nil
}

// ensureTopic creates the topic when it is missing. One partition matches the
// local Redpanda topic. Replication factor -1 uses the broker default, which
// is 1 on Redpanda and the managed factor on MSK Serverless.
func ensureTopic(ctx context.Context, client *kgo.Client, topic string) error {
	_, err := kadm.NewClient(client).CreateTopic(ctx, 1, -1, nil, topic)
	if err != nil && !topicAlreadyExists(err) {
		return fmt.Errorf("create topic %s: %w", topic, err)
	}
	return nil
}

func topicAlreadyExists(err error) bool {
	var kafkaErr *kerr.Error
	return errors.As(err, &kafkaErr) && kafkaErr.Code == kerr.TopicAlreadyExists.Code
}

func waitBeforeEvent(ctx context.Context, cfg PublishConfig, pace *time.Ticker) error {
	if cfg.SleepMax > 0 {
		delay := SleepDuration(cfg.SleepMin, cfg.SleepMax, cfg.Rand)
		timer := time.NewTimer(delay)
		defer timer.Stop()
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-timer.C:
			return nil
		}
	}
	if pace == nil {
		return nil
	}
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-pace.C:
		return nil
	}
}

// SleepDuration returns a pause from min through max, inclusive.
func SleepDuration(min, max time.Duration, rng *rand.Rand) time.Duration {
	span := int64(max - min)
	if span == 0 {
		return min
	}
	return min + time.Duration(rng.Int64N(span+1))
}

func encode(event Event, useGlue bool, schemaID [16]byte) ([]byte, error) {
	message := &v1.DocumentEvent{
		TenantId:   event.TenantID,
		DocumentId: event.DocumentID,
		Version:    event.Version,
		Operation:  event.Operation,
		Markdown:   event.Markdown,
	}
	payload, err := proto.Marshal(message)
	if err != nil {
		return nil, fmt.Errorf("marshal %s: %w", event.DocumentID, err)
	}
	if !useGlue {
		return payload, nil
	}
	return gluewire.Encode(schemaID, payload), nil
}

func operationName(op v1.Operation) string {
	switch op {
	case v1.Operation_OPERATION_INSERT:
		return "INSERT"
	case v1.Operation_OPERATION_UPDATE:
		return "UPDATE"
	case v1.Operation_OPERATION_DELETE:
		return "DELETE"
	default:
		return op.String()
	}
}

func mskIAM() (sasl.Mechanism, error) {
	awsCfg, err := config.LoadDefaultConfig(context.Background())
	if err != nil {
		return nil, fmt.Errorf("aws config: %w", err)
	}
	return franzaws.ManagedStreamingIAM(func(ctx context.Context) (franzaws.Auth, error) {
		creds, err := awsCfg.Credentials.Retrieve(ctx)
		if err != nil {
			return franzaws.Auth{}, err
		}
		return franzaws.Auth{
			AccessKey:    creds.AccessKeyID,
			SecretKey:    creds.SecretAccessKey,
			SessionToken: creds.SessionToken,
			UserAgent:    "rag-demo-producer",
		}, nil
	}), nil
}

// SplitList splits a comma-separated flag value.
func SplitList(value string) []string {
	parts := strings.Split(value, ",")
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if part != "" {
			out = append(out, part)
		}
	}
	return out
}
