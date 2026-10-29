package com.sarna.rag.flink;

import com.sarna.rag.v1.DocumentChunk;
import com.sarna.rag.v1.DocumentEmbedding;
import java.util.Collections;
import java.util.concurrent.TimeoutException;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.streaming.api.functions.async.ResultFuture;
import org.apache.flink.streaming.api.functions.async.RichAsyncFunction;

/** Calls the embedder without waiting in the main task thread. An empty chunk skips the call. */
public final class EmbedFunction extends RichAsyncFunction<DocumentChunk, DocumentEmbedding> {
  private final String endpoint;
  private transient EmbedClient client;

  EmbedFunction(String endpoint) {
    this.endpoint = endpoint;
  }

  static boolean needsEmbed(String text) {
    return text != null && !text.isEmpty();
  }

  @Override
  public void open(OpenContext openContext) {
    client = EmbedClient.create(endpoint);
  }

  @Override
  public void asyncInvoke(DocumentChunk chunk, ResultFuture<DocumentEmbedding> future) {
    if (!needsEmbed(chunk.getText())) {
      future.complete(Collections.singleton(embedding(chunk, new float[0])));
      return;
    }
    client
        .embed(chunk.getText())
        .whenComplete(
            (vector, error) -> {
              if (error != null) {
                future.completeExceptionally(error);
                return;
              }
              future.complete(Collections.singleton(embedding(chunk, vector)));
            });
  }

  @Override
  public void timeout(DocumentChunk chunk, ResultFuture<DocumentEmbedding> future) {
    future.completeExceptionally(new TimeoutException("embed timed out"));
  }

  private static DocumentEmbedding embedding(DocumentChunk chunk, float[] vector) {
    DocumentEmbedding.Builder builder =
        DocumentEmbedding.newBuilder()
            .setTenantId(chunk.getTenantId())
            .setDocumentId(chunk.getDocumentId())
            .setVersion(chunk.getVersion())
            .setOperation(chunk.getOperation())
            .setChunkIndex(chunk.getChunkIndex())
            .setHeading(chunk.getHeading())
            .setText(chunk.getText());
    for (float value : vector) {
      builder.addVector(value);
    }
    return builder.build();
  }
}
