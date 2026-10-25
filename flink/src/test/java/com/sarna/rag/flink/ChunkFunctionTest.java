package com.sarna.rag.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sarna.rag.v1.DocumentChunk;
import com.sarna.rag.v1.DocumentEvent;
import com.sarna.rag.v1.Operation;
import java.util.ArrayList;
import java.util.List;
import org.apache.flink.util.Collector;
import org.junit.jupiter.api.Test;

class ChunkFunctionTest {
  @Test
  void insertThenUpdateStayAsSeparateVersions() {
    ChunkFunction function = new ChunkFunction();
    DocumentEvent insert = event(1, Operation.OPERATION_INSERT, "# Title\n\nbody\n");
    DocumentEvent update = event(2, Operation.OPERATION_UPDATE, "# Title\n\nbody\n\nchanged\n");

    List<DocumentChunk> first = collect(function, insert);
    List<DocumentChunk> second = collect(function, update);

    assertEquals(1, first.size());
    assertEquals(1, first.get(0).getVersion());
    assertEquals(Operation.OPERATION_INSERT, first.get(0).getOperation());
    assertEquals(1, second.size());
    assertEquals(2, second.get(0).getVersion());
    assertEquals(Operation.OPERATION_UPDATE, second.get(0).getOperation());
    assertEquals(false, VersionDedup.keep(insert.getVersion(), insert.getVersion()));
    assertEquals(true, VersionDedup.keep(insert.getVersion(), update.getVersion()));
  }

  @Test
  void deleteIsOneEmptyChunk() {
    List<DocumentChunk> chunks =
        collect(new ChunkFunction(), event(3, Operation.OPERATION_DELETE, ""));
    assertEquals(1, chunks.size());
    assertEquals(0, chunks.get(0).getChunkIndex());
    assertEquals("", chunks.get(0).getText());
    assertEquals(Operation.OPERATION_DELETE, chunks.get(0).getOperation());
  }

  private static DocumentEvent event(long version, Operation operation, String markdown) {
    return DocumentEvent.newBuilder()
        .setTenantId("tenant-alpha")
        .setDocumentId("keps/example")
        .setVersion(version)
        .setOperation(operation)
        .setMarkdown(markdown)
        .build();
  }

  private static List<DocumentChunk> collect(ChunkFunction function, DocumentEvent event) {
    List<DocumentChunk> chunks = new ArrayList<>();
    try {
      function.flatMap(
          event,
          new Collector<>() {
            @Override
            public void collect(DocumentChunk record) {
              chunks.add(record);
            }

            @Override
            public void close() {}
          });
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
    return chunks;
  }
}
