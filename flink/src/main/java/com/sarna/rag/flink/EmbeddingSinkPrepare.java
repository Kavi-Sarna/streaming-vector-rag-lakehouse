package com.sarna.rag.flink;

import com.sarna.rag.v1.DocumentEmbedding;
import com.sarna.rag.v1.Operation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/**
 * Turns an accepted embedding into the Iceberg and Redis writes for that document. A newer version
 * tombstones chunk indexes the new version does not keep. A delete tombstones every stored chunk.
 */
public final class EmbeddingSinkPrepare
    extends KeyedProcessFunction<String, DocumentEmbedding, DocumentEmbedding> {
  private transient ValueState<Long> versionState;
  private transient MapState<Integer, Boolean> chunksState;

  record Advance(long version, Set<Integer> indexes, List<DocumentEmbedding> writes) {}

  static Advance advance(Long version, Set<Integer> indexes, DocumentEmbedding incoming) {
    TreeSet<Integer> current = new TreeSet<>();
    if (indexes != null) {
      current.addAll(indexes);
    }
    if (version != null && incoming.getVersion() < version) {
      return new Advance(version, Set.copyOf(current), List.of());
    }
    if (incoming.getOperation() == Operation.OPERATION_DELETE) {
      TreeSet<Integer> doomed = new TreeSet<>(current);
      doomed.add(incoming.getChunkIndex());
      List<DocumentEmbedding> writes = new ArrayList<>();
      for (int index : doomed) {
        writes.add(tombstone(incoming, index));
      }
      return new Advance(incoming.getVersion(), Set.of(), writes);
    }
    if (version == null || incoming.getVersion() > version) {
      List<DocumentEmbedding> writes = new ArrayList<>();
      for (int index : current) {
        if (index != incoming.getChunkIndex()) {
          writes.add(tombstone(incoming, index));
        }
      }
      writes.add(incoming);
      return new Advance(incoming.getVersion(), Set.of(incoming.getChunkIndex()), writes);
    }
    current.add(incoming.getChunkIndex());
    return new Advance(version, Set.copyOf(current), List.of(incoming));
  }

  static DocumentEmbedding tombstone(DocumentEmbedding incoming, int chunkIndex) {
    return DocumentEmbedding.newBuilder()
        .setTenantId(incoming.getTenantId())
        .setDocumentId(incoming.getDocumentId())
        .setVersion(incoming.getVersion())
        .setOperation(Operation.OPERATION_DELETE)
        .setChunkIndex(chunkIndex)
        .build();
  }

  @Override
  public void open(OpenContext openContext) {
    versionState = getRuntimeContext().getState(new ValueStateDescriptor<>("sink-version", Long.class));
    chunksState =
        getRuntimeContext().getMapState(new MapStateDescriptor<>("sink-chunks", Integer.class, Boolean.class));
  }

  @Override
  public void processElement(
      DocumentEmbedding embedding, Context context, Collector<DocumentEmbedding> out) throws Exception {
    TreeSet<Integer> indexes = new TreeSet<>();
    for (Integer index : chunksState.keys()) {
      indexes.add(index);
    }
    Advance advance = advance(versionState.value(), indexes, embedding);
    versionState.update(advance.version());
    chunksState.clear();
    for (int index : advance.indexes()) {
      chunksState.put(index, true);
    }
    for (DocumentEmbedding write : advance.writes()) {
      out.collect(write);
    }
  }
}
