package com.sarna.rag.flink;

import com.sarna.rag.v1.DocumentChunk;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/** Sends a chunk to the embedder when its version is at least the newest one requested. */
public final class EmbeddingDispatch extends KeyedProcessFunction<String, DocumentChunk, DocumentChunk> {
  private transient ValueState<Long> requestedVersion;

  static boolean dispatch(Long requested, long version) {
    return requested == null || version >= requested;
  }

  @Override
  public void open(OpenContext openContext) {
    requestedVersion =
        getRuntimeContext().getState(new ValueStateDescriptor<>("requested-version", Long.class));
  }

  @Override
  public void processElement(DocumentChunk chunk, Context context, Collector<DocumentChunk> out)
      throws Exception {
    Long requested = requestedVersion.value();
    if (!dispatch(requested, chunk.getVersion())) {
      return;
    }
    if (requested == null || chunk.getVersion() > requested) {
      requestedVersion.update(chunk.getVersion());
    }
    out.collect(chunk);
  }
}
