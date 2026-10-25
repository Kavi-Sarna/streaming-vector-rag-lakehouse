package com.sarna.rag.flink;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersionDedupTest {
  @Test
  void firstVersionIsKept() {
    assertTrue(VersionDedup.keep(null, 1));
  }

  @Test
  void sameVersionIsDropped() {
    assertFalse(VersionDedup.keep(1L, 1));
  }

  @Test
  void newerVersionIsKept() {
    assertTrue(VersionDedup.keep(1L, 2));
  }

  @Test
  void olderVersionIsDropped() {
    assertFalse(VersionDedup.keep(2L, 1));
  }
}
