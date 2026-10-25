package com.sarna.rag.flink;

import com.sarna.rag.v1.DocumentChunk;
import org.apache.flink.api.common.functions.MapFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes one taskmanager line per chunk so the local run can be grepped. */
public final class ChunkLog implements MapFunction<DocumentChunk, DocumentChunk> {
  private static final Logger LOG = LoggerFactory.getLogger(ChunkLog.class);

  @Override
  public DocumentChunk map(DocumentChunk chunk) {
    LOG.info(
        "Chunk emitted document_id={} version={} chunk_index={} operation={}",
        chunk.getDocumentId(),
        chunk.getVersion(),
        chunk.getChunkIndex(),
        chunk.getOperation());
    return chunk;
  }
}
