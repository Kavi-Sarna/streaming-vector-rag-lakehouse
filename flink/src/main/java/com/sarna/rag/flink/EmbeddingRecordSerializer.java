package com.sarna.rag.flink;

import com.sarna.rag.v1.DocumentEmbedding;
import java.nio.charset.StandardCharsets;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.kafka.clients.producer.ProducerRecord;

/** Writes a raw Protobuf DocumentEmbedding, keyed by document id. */
public final class EmbeddingRecordSerializer
    implements KafkaRecordSerializationSchema<DocumentEmbedding> {
  private final String topic;

  public EmbeddingRecordSerializer(String topic) {
    this.topic = topic;
  }

  @Override
  public ProducerRecord<byte[], byte[]> serialize(
      DocumentEmbedding element, KafkaSinkContext context, Long timestamp) {
    return new ProducerRecord<>(
        topic, element.getDocumentId().getBytes(StandardCharsets.UTF_8), element.toByteArray());
  }
}
