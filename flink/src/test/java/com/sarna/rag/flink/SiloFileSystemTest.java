package com.sarna.rag.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.apache.hadoop.fs.FSDataInputStream;
import org.junit.jupiter.api.Test;

class SiloFileSystemTest {
  @Test
  void openStreamSeeksInsideTheObject() throws Exception {
    byte[] bytes = "version-hint".getBytes(StandardCharsets.UTF_8);
    try (FSDataInputStream input = SiloFileSystem.input(bytes)) {
      input.seek(8);
      assertEquals('h', input.read());
      assertEquals(9, input.getPos());
    }
  }
}
