package com.sarna.rag.flink;

import com.sarna.rag.v1.DocumentChunk;
import java.nio.charset.StandardCharsets;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.kafka.clients.producer.ProducerRecord;

/** Writes a raw Protobuf DocumentChunk, keyed by document id. */
public final class ChunkRecordSerializer implements KafkaRecordSerializationSchema<DocumentChunk> {
  private final String topic;

  public ChunkRecordSerializer(String topic) {
    this.topic = topic;
  }

  @Override
  public ProducerRecord<byte[], byte[]> serialize(
      DocumentChunk element, KafkaSinkContext context, Long timestamp) {
    return new ProducerRecord<>(
        topic, element.getDocumentId().getBytes(StandardCharsets.UTF_8), element.toByteArray());
  }
}
