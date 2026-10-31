package com.sarna.rag.flink;

import com.sarna.rag.v1.DocumentChunk;
import com.sarna.rag.v1.DocumentEmbedding;
import com.sarna.rag.v1.DocumentEvent;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.AsyncDataStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.data.RowData;
import org.apache.iceberg.flink.TableLoader;
import org.apache.iceberg.flink.sink.FlinkSink;

/** Reads document.events.v1, drops a stale version, chunks markdown, embeds the kept chunks, and writes Iceberg and Redis. */
public final class DedupJob {
  static final String JOB_NAME = "dedup-and-chunk";

  private DedupJob() {}

  public static void main(String[] args) throws Exception {
    String brokers = arg(args, "--brokers", "redpanda:9093");
    String inputTopic = arg(args, "--input-topic", "document.events.v1");
    String outputTopic = arg(args, "--output-topic", "document.chunks.v1");
    String embeddingTopic = arg(args, "--embedding-topic", "document.embeddings.v1");
    String embedder = arg(args, "--embedder", "mock");
    String embedUrl = arg(args, "--embed-url", "http://mock-embedder:8080/embed");
    String bedrockRegion = arg(args, "--bedrock-region", "us-east-1");
    String groupId = arg(args, "--group-id", "rag-flink-dedup");
    String redisHost = arg(args, "--redis-host", "redis");
    int redisPort = Integer.parseInt(arg(args, "--redis-port", "6379"));
    String warehouse = arg(args, "--iceberg-warehouse", "s3://lakehouse/warehouse");
    String s3Endpoint = arg(args, "--s3-endpoint", "http://minio:9000");
    String s3AccessKey = arg(args, "--s3-access-key", "minio");
    String s3SecretKey = arg(args, "--s3-secret-key", "minio12345");
    String s3Region = arg(args, "--s3-region", "us-east-1");

    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    env.setParallelism(1);
    env.enableCheckpointing(10_000);

    KafkaSource<DocumentEvent> source =
        KafkaSource.<DocumentEvent>builder()
            .setBootstrapServers(brokers)
            .setTopics(inputTopic)
            .setGroupId(groupId)
            .setStartingOffsets(OffsetsInitializer.earliest())
            .setDeserializer(new DocumentEventDeserializer())
            .build();

    DataStream<DocumentChunk> chunks =
        env.fromSource(source, org.apache.flink.api.common.eventtime.WatermarkStrategy.noWatermarks(), "document-events")
            .uid("document-events")
            .keyBy(DocumentEvent::getDocumentId)
            .process(new VersionDedup())
            .uid("version-dedup")
            .name("version-dedup")
            .flatMap(new ChunkFunction())
            .uid("chunk")
            .name("chunk")
            .map(new ChunkLog())
            .uid("chunk-log")
            .name("chunk-log");

    KafkaSink<DocumentChunk> sink =
        KafkaSink.<DocumentChunk>builder()
            .setBootstrapServers(brokers)
            .setRecordSerializer(new ChunkRecordSerializer(outputTopic))
            .build();
    chunks.sinkTo(sink).uid("document-chunks").name("document-chunks");

    DataStream<DocumentEmbedding> embeddings =
        AsyncDataStream.unorderedWait(
                chunks
                    .keyBy(DocumentChunk::getDocumentId)
                    .process(new EmbeddingDispatch())
                    .uid("embed-dispatch")
                    .name("embed-dispatch"),
                new EmbedFunction(embedder, embedUrl, bedrockRegion),
                5,
                TimeUnit.SECONDS,
                16)
            .uid("embed")
            .name("embed")
            .keyBy(DocumentEmbedding::getDocumentId)
            .process(new EmbeddingAccept())
            .uid("embed-accept")
            .name("embed-accept")
            .map(new EmbeddingLog())
            .uid("embed-log")
            .name("embed-log");

    KafkaSink<DocumentEmbedding> embeddingSink =
        KafkaSink.<DocumentEmbedding>builder()
            .setBootstrapServers(brokers)
            .setRecordSerializer(new EmbeddingRecordSerializer(embeddingTopic))
            .build();
    embeddings.sinkTo(embeddingSink).uid("document-embeddings").name("document-embeddings");

    DataStream<DocumentEmbedding> stored =
        embeddings
            .keyBy(DocumentEmbedding::getDocumentId)
            .process(new EmbeddingSinkPrepare())
            .uid("sink-prepare")
            .name("sink-prepare");

    TableLoader tableLoader =
        IcebergTables.ensure(warehouse, s3Endpoint, s3AccessKey, s3SecretKey, s3Region);
    DataStream<RowData> rows = stored.map(new EmbeddingRow()).uid("iceberg-row").name("iceberg-row");
    FlinkSink.forRowData(rows)
        .tableLoader(tableLoader)
        .upsert(true)
        .equalityFieldColumns(EmbeddingRow.EQUALITY_FIELDS)
        .uidPrefix("iceberg-embeddings")
        .append();

    stored
        .addSink(new RedisVectorSink(redisHost, redisPort))
        .uid("redis-vectors")
        .name("redis-vectors");

    env.execute(JOB_NAME);
  }

  static String arg(String[] args, String name, String fallback) {
    for (int index = 0; index < args.length - 1; index++) {
      if (name.equals(args[index])) {
        return args[index + 1];
      }
    }
    if (Arrays.asList(args).contains(name)) {
      throw new IllegalArgumentException(name + " needs a value");
    }
    return fallback;
  }
}
