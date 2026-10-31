package com.sarna.rag.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sarna.rag.v1.DocumentEmbedding;
import com.sarna.rag.v1.Operation;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EmbeddingSinkPrepareTest {
  @Test
  void newerVersionTombstonesChunksItDoesNotKeep() {
    EmbeddingSinkPrepare.Advance advance =
        EmbeddingSinkPrepare.advance(1L, Set.of(0, 1), embedding(2, 0, Operation.OPERATION_UPDATE));

    assertEquals(2L, advance.version());
    assertEquals(Set.of(0), advance.indexes());
    assertEquals(2, advance.writes().size());
    assertEquals(Operation.OPERATION_DELETE, advance.writes().get(0).getOperation());
    assertEquals(1, advance.writes().get(0).getChunkIndex());
    assertEquals(Operation.OPERATION_UPDATE, advance.writes().get(1).getOperation());
    assertEquals(0, advance.writes().get(1).getChunkIndex());
  }

  @Test
  void sameVersionKeepsEveryChunk() {
    EmbeddingSinkPrepare.Advance advance =
        EmbeddingSinkPrepare.advance(2L, Set.of(0), embedding(2, 1, Operation.OPERATION_INSERT));

    assertEquals(Set.of(0, 1), advance.indexes());
    assertEquals(List.of(1), advance.writes().stream().map(DocumentEmbedding::getChunkIndex).toList());
  }

  @Test
  void olderVersionIsDropped() {
    EmbeddingSinkPrepare.Advance advance =
        EmbeddingSinkPrepare.advance(3L, Set.of(0), embedding(2, 0, Operation.OPERATION_UPDATE));

    assertEquals(3L, advance.version());
    assertEquals(Set.of(0), advance.indexes());
    assertTrue(advance.writes().isEmpty());
  }

  @Test
  void deleteTombstonesEveryStoredChunk() {
    EmbeddingSinkPrepare.Advance advance =
        EmbeddingSinkPrepare.advance(2L, Set.of(0, 1), embedding(3, 0, Operation.OPERATION_DELETE));

    assertEquals(3L, advance.version());
    assertTrue(advance.indexes().isEmpty());
    assertEquals(List.of(0, 1), advance.writes().stream().map(DocumentEmbedding::getChunkIndex).toList());
    assertTrue(advance.writes().stream().allMatch(row -> row.getOperation() == Operation.OPERATION_DELETE));
    assertEquals(0, advance.writes().get(0).getVectorCount());
  }

  private static DocumentEmbedding embedding(long version, int chunkIndex, Operation operation) {
    return DocumentEmbedding.newBuilder()
        .setTenantId("tenant-alpha")
        .setDocumentId("doc")
        .setVersion(version)
        .setOperation(operation)
        .setChunkIndex(chunkIndex)
        .setHeading("Deploy")
        .setText("steps")
        .addVector(0.25f)
        .build();
  }
}
