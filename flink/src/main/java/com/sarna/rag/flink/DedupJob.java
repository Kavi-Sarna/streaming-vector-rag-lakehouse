package com.sarna.rag.flink;

import com.sarna.rag.v1.DocumentChunk;
import com.sarna.rag.v1.DocumentEvent;
import java.util.Arrays;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/** Reads document.events.v1, drops a version already applied, and writes document.chunks.v1. */
public final class DedupJob {
  static final String JOB_NAME = "dedup-and-chunk";

  private DedupJob() {}

  public static void main(String[] args) throws Exception {
    String brokers = arg(args, "--brokers", "redpanda:9093");
    String inputTopic = arg(args, "--input-topic", "document.events.v1");
    String outputTopic = arg(args, "--output-topic", "document.chunks.v1");
    String groupId = arg(args, "--group-id", "rag-flink-dedup");

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
