package com.sarna.rag.flink;

import java.util.concurrent.CompletableFuture;

/** Turns chunk text into a vector. The mock embedder and Titan both implement this. */
interface VectorEmbedder {
  CompletableFuture<float[]> embed(String text);
}
