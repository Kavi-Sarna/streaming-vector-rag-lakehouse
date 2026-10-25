package com.sarna.rag.flink;

import com.google.protobuf.InvalidProtocolBufferException;
import com.sarna.rag.v1.DocumentEvent;
import java.io.IOException;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads a raw Protobuf DocumentEvent. A record that does not parse is skipped. */
public final class DocumentEventDeserializer implements KafkaRecordDeserializationSchema<DocumentEvent> {
  private static final Logger LOG = LoggerFactory.getLogger(DocumentEventDeserializer.class);

  @Override
  public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<DocumentEvent> out)
      throws IOException {
    byte[] value = record.value();
    if (value == null || value.length == 0) {
      return;
    }
    try {
      out.collect(DocumentEvent.parseFrom(value));
    } catch (InvalidProtocolBufferException exception) {
      LOG.warn("Dropped a record that is not a raw DocumentEvent");
    }
  }

  @Override
  public TypeInformation<DocumentEvent> getProducedType() {
    return TypeInformation.of(DocumentEvent.class);
  }
}
