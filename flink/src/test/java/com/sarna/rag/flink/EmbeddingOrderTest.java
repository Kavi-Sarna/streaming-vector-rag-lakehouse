package com.sarna.rag.flink;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EmbeddingOrderTest {
  @Test
  void firstChunkIsDispatched() {
    assertTrue(EmbeddingDispatch.dispatch(null, 1));
  }

  @Test
  void sameVersionChunksAreDispatched() {
    assertTrue(EmbeddingDispatch.dispatch(2L, 2));
  }

  @Test
  void olderVersionIsNotDispatched() {
    assertFalse(EmbeddingDispatch.dispatch(2L, 1));
  }

  @Test
  void lateOlderEmbeddingIsDropped() {
    assertFalse(EmbeddingAccept.accept(3L, 1));
  }

  @Test
  void sameVersionEmbeddingIsAccepted() {
    assertTrue(EmbeddingAccept.accept(2L, 2));
  }

  @Test
  void newerEmbeddingIsAccepted() {
    assertTrue(EmbeddingAccept.accept(1L, 2));
  }
}
