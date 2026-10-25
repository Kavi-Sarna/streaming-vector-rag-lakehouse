package com.sarna.rag.flink;

import com.sarna.rag.v1.DocumentChunk;
import com.sarna.rag.v1.DocumentEvent;
import java.util.List;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.util.Collector;

/** Turns one kept DocumentEvent into structural chunks. A delete is one empty chunk. */
public final class ChunkFunction implements FlatMapFunction<DocumentEvent, DocumentChunk> {
  @Override
  public void flatMap(DocumentEvent event, Collector<DocumentChunk> out) {
    List<MarkdownChunker.Section> sections = MarkdownChunker.chunk(event.getMarkdown());
    if (sections.isEmpty()) {
      out.collect(chunk(event, 0, "", ""));
      return;
    }
    for (int index = 0; index < sections.size(); index++) {
      MarkdownChunker.Section section = sections.get(index);
      out.collect(chunk(event, index, section.heading(), section.text()));
    }
  }

  private static DocumentChunk chunk(DocumentEvent event, int index, String heading, String text) {
    return DocumentChunk.newBuilder()
        .setTenantId(event.getTenantId())
        .setDocumentId(event.getDocumentId())
        .setVersion(event.getVersion())
        .setOperation(event.getOperation())
        .setChunkIndex(index)
        .setHeading(heading)
        .setText(text)
        .build();
  }
}
