package com.sarna.rag.flink;

import com.sarna.rag.v1.DocumentEmbedding;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/** Drops an embedding that finishes after a newer version of the same document. */
public final class EmbeddingAccept extends KeyedProcessFunction<String, DocumentEmbedding, DocumentEmbedding> {
  private transient ValueState<Long> acceptedVersion;

  static boolean accept(Long accepted, long version) {
    return accepted == null || version >= accepted;
  }

  @Override
  public void open(OpenContext openContext) {
    acceptedVersion =
        getRuntimeContext().getState(new ValueStateDescriptor<>("accepted-version", Long.class));
  }

  @Override
  public void processElement(
      DocumentEmbedding embedding, Context context, Collector<DocumentEmbedding> out) throws Exception {
    Long accepted = acceptedVersion.value();
    if (!accept(accepted, embedding.getVersion())) {
      return;
    }
    if (accepted == null || embedding.getVersion() > accepted) {
      acceptedVersion.update(embedding.getVersion());
    }
    out.collect(embedding);
  }
}
