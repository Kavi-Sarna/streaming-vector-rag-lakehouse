package com.sarna.rag.flink;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** POSTs chunk text to the mock embedder and parses the returned vector. */
public final class EmbedClient implements VectorEmbedder {
  private final HttpClient http;
  private final URI endpoint;
  private final EmbedCircuit circuit;
  private final Clock clock;

  EmbedClient(HttpClient http, URI endpoint, EmbedCircuit circuit, Clock clock) {
    this.http = http;
    this.endpoint = endpoint;
    this.circuit = circuit;
    this.clock = clock;
  }

  static EmbedClient create(String endpoint) {
    HttpClient http =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).version(HttpClient.Version.HTTP_1_1).build();
    return new EmbedClient(http, URI.create(endpoint), new EmbedCircuit(3, 5_000), System::currentTimeMillis);
  }

  @Override
  public CompletableFuture<float[]> embed(String text) {
    long now = clock.millis();
    if (!circuit.allow(now)) {
      return CompletableFuture.failedFuture(new IOException("embedder circuit open"));
    }
    HttpRequest request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(4))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonText(text)))
            .build();
    return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .handle(
            (response, error) -> {
              if (error != null || response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
                circuit.failure(clock.millis());
                String message =
                    error != null
                        ? error.getMessage()
                        : "embedder returned " + (response == null ? "no body" : response.statusCode());
                throw new EmbedException(message == null ? "embed failed" : message);
              }
              float[] vector = parseVector(response.body());
              circuit.success();
              return vector;
            });
  }

  static String jsonText(String text) {
    return "{\"text\":\"" + escapeJson(text) + "\"}";
  }

  static String escapeJson(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '"' -> out.append("\\\"");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }

  static float[] parseVector(String body) {
    int start = body.indexOf('[');
    int end = body.indexOf(']', start + 1);
    if (start < 0 || end < 0) {
      throw new EmbedException("embed response has no vector");
    }
    String inside = body.substring(start + 1, end).trim();
    if (inside.isEmpty()) {
      return new float[0];
    }
    String[] parts = inside.split(",");
    float[] vector = new float[parts.length];
    for (int i = 0; i < parts.length; i++) {
      vector[i] = Float.parseFloat(parts[i].trim());
    }
    return vector;
  }

  interface Clock {
    long millis();
  }

  static final class EmbedException extends RuntimeException {
    EmbedException(String message) {
      super(message);
    }
  }
}
