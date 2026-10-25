package com.sarna.rag.flink;

import com.sarna.rag.v1.DocumentEvent;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/** Keeps a document version only when it is newer than the one already applied. */
public final class VersionDedup extends KeyedProcessFunction<String, DocumentEvent, DocumentEvent> {
  private transient ValueState<Long> seenVersion;

  static boolean keep(Long seen, long version) {
    return seen == null || version > seen;
  }

  @Override
  public void open(OpenContext openContext) {
    seenVersion = getRuntimeContext().getState(new ValueStateDescriptor<>("seen-version", Long.class));
  }

  @Override
  public void processElement(DocumentEvent event, Context context, Collector<DocumentEvent> out)
      throws Exception {
    Long seen = seenVersion.value();
    if (!keep(seen, event.getVersion())) {
      return;
    }
    seenVersion.update(event.getVersion());
    out.collect(event);
  }
}
