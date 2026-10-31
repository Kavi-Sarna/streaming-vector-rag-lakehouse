package com.sarna.rag.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedisVersionGateTest {
  @Test
  void keepsTheIncomingVersionWhenItIsAtLeastTheStoredOne() {
    assertTrue(RedisVersionGate.allow(null, 1));
    assertTrue(RedisVersionGate.allow("", 1));
    assertTrue(RedisVersionGate.allow("2", 2));
    assertTrue(RedisVersionGate.allow("2", 3));
    assertFalse(RedisVersionGate.allow("3", 2));
  }

  @Test
  void keyIsTenantDocumentAndChunk() {
    assertEquals("tenant-alpha:keps/node:1", RedisVersionGate.key("tenant-alpha", "keps/node", 1));
  }

  @Test
  void upsertScriptMatchesTheVersionCheck() {
    assertTrue(RedisVersionGate.UPSERT.contains("tonumber(ARGV[1]) >= tonumber(current_ver)"));
    assertTrue(RedisVersionGate.UPSERT.contains("'version', ARGV[1], 'vector', ARGV[2], 'content', ARGV[3]"));
    assertTrue(RedisVersionGate.DELETE.contains("redis.call('DEL', KEYS[1])"));
    assertTrue(RedisVersionGate.DELETE.contains("tonumber(ARGV[1]) >= tonumber(current_ver)"));
  }

  @Test
  void encodesTheVectorAsLittleEndianFloats() {
    byte[] encoded = RedisVersionGate.encodeVector(List.of(1f, -0.5f));
    ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(1f, buffer.getFloat());
    assertEquals(-0.5f, buffer.getFloat());
    assertEquals(0, RedisVersionGate.encodeVector(List.of()).length);
  }
}
