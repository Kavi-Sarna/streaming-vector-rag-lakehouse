package com.sarna.rag.flink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Redis hash key and the Lua scripts that keep the newest version of a chunk. */
final class RedisVersionGate {
  static final String UPSERT =
      """
      local current_ver = redis.call('HGET', KEYS[1], 'version')
      if not current_ver or tonumber(ARGV[1]) >= tonumber(current_ver) then
          redis.call('HSET', KEYS[1], 'version', ARGV[1], 'vector', ARGV[2], 'content', ARGV[3])
          return 1
      end
      return 0
      """;

  static final String DELETE =
      """
      local current_ver = redis.call('HGET', KEYS[1], 'version')
      if not current_ver or tonumber(ARGV[1]) >= tonumber(current_ver) then
          redis.call('DEL', KEYS[1])
          return 1
      end
      return 0
      """;

  private RedisVersionGate() {}

  static String key(String tenantId, String documentId, int chunkIndex) {
    return tenantId + ":" + documentId + ":" + chunkIndex;
  }

  static boolean allow(String current, long incoming) {
    return current == null || current.isEmpty() || incoming >= Long.parseLong(current);
  }

  static byte[] encodeVector(List<Float> values) {
    ByteBuffer buffer = ByteBuffer.allocate(values.size() * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    for (Float value : values) {
      buffer.putFloat(value);
    }
    return buffer.array();
  }

  static byte[] script(boolean delete) {
    return (delete ? DELETE : UPSERT).getBytes(StandardCharsets.UTF_8);
  }
}
