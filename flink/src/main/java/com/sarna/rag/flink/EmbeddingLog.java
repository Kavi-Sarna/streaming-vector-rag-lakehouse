package com.sarna.rag.flink;

import com.sarna.rag.v1.DocumentEmbedding;
import org.apache.flink.api.common.functions.MapFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes one taskmanager line per accepted embedding. */
public final class EmbeddingLog implements MapFunction<DocumentEmbedding, DocumentEmbedding> {
  private static final Logger LOG = LoggerFactory.getLogger(EmbeddingLog.class);

  @Override
  public DocumentEmbedding map(DocumentEmbedding embedding) {
    if (embedding.getVectorCount() == 0) {
      LOG.info(
          "Embedding skipped document_id={} version={} chunk_index={} operation={}",
          embedding.getDocumentId(),
          embedding.getVersion(),
          embedding.getChunkIndex(),
          embedding.getOperation());
      return embedding;
    }
    LOG.info(
        "Vector embedded successfully document_id={} version={} chunk_index={} dimensions={}",
        embedding.getDocumentId(),
        embedding.getVersion(),
        embedding.getChunkIndex(),
        embedding.getVectorCount());
    return embedding;
  }
}
