package com.sarna.rag.flink;

/** Opens after consecutive embedder failures and allows one trial after the cooldown. */
final class EmbedCircuit {
  private final int failureLimit;
  private final long openMillis;
  private int failures;
  private long openUntil;

  EmbedCircuit(int failureLimit, long openMillis) {
    this.failureLimit = failureLimit;
    this.openMillis = openMillis;
  }

  synchronized boolean allow(long now) {
    if (openUntil == 0) {
      return true;
    }
    if (now < openUntil) {
      return false;
    }
    openUntil = 0;
    failures = failureLimit - 1;
    return true;
  }

  synchronized void success() {
    failures = 0;
    openUntil = 0;
  }

  synchronized void failure(long now) {
    failures++;
    if (failures >= failureLimit) {
      openUntil = now + openMillis;
      failures = 0;
    }
  }
}
