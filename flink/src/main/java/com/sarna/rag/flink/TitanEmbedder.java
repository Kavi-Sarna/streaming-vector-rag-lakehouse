package com.sarna.rag.flink;

import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;

/** Calls Titan Text Embeddings V2 and keeps a 1024-dimension normalized vector. */
public final class TitanEmbedder implements VectorEmbedder {
  static final String MODEL_ID = "amazon.titan-embed-text-v2:0";
  static final int DIMENSIONS = 1024;

  private final BedrockRuntimeClient bedrock;
  private final EmbedCircuit circuit;
  private final EmbedClient.Clock clock;

  TitanEmbedder(BedrockRuntimeClient bedrock, EmbedCircuit circuit, EmbedClient.Clock clock) {
    this.bedrock = bedrock;
    this.circuit = circuit;
    this.clock = clock;
  }

  static TitanEmbedder create(String region) {
    BedrockRuntimeClient bedrock =
        BedrockRuntimeClient.builder()
            .region(Region.of(region))
            .httpClient(UrlConnectionHttpClient.create())
            .build();
    return new TitanEmbedder(bedrock, new EmbedCircuit(3, 5_000), System::currentTimeMillis);
  }

  static String requestJson(String text) {
    return "{\"inputText\":\""
        + EmbedClient.escapeJson(text)
        + "\",\"dimensions\":"
        + DIMENSIONS
        + ",\"normalize\":true}";
  }

  static float[] parseEmbedding(String body) {
    int key = body.indexOf("\"embedding\"");
    if (key < 0) {
      throw new EmbedClient.EmbedException("Titan response has no embedding");
    }
    return EmbedClient.parseVector(body.substring(key));
  }

  static float[] requireWidth(float[] vector) {
    if (vector.length != DIMENSIONS) {
      throw new EmbedClient.EmbedException("Titan returned " + vector.length + " dimensions");
    }
    return vector;
  }

  @Override
  public CompletableFuture<float[]> embed(String text) {
    long now = clock.millis();
    if (!circuit.allow(now)) {
      return CompletableFuture.failedFuture(new java.io.IOException("embedder circuit open"));
    }
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            InvokeModelRequest request =
                InvokeModelRequest.builder()
                    .modelId(MODEL_ID)
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(requestJson(text)))
                    .build();
            float[] vector = requireWidth(parseEmbedding(bedrock.invokeModel(request).body().asUtf8String()));
            circuit.success();
            return vector;
          } catch (RuntimeException error) {
            circuit.failure(clock.millis());
            throw error;
          }
        });
  }
}
