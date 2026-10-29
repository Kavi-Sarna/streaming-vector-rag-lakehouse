package com.sarna.rag.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class EmbedClientTest {
  @Test
  void escapesTextAndParsesTheVector() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/embed",
        exchange -> {
          calls.incrementAndGet();
          String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          assertEquals("{\"text\":\"a\\\"b\\nc\"}", body);
          byte[] response = "{\"dimensions\":2,\"vector\":[0.5, -1]}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try {
      EmbedClient client = client(server, new AtomicLong(0));
      float[] vector = client.embed("a\"b\nc").get();
      assertEquals(2, vector.length);
      assertEquals(0.5f, vector[0]);
      assertEquals(-1f, vector[1]);
      assertEquals(1, calls.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void opensAfterThreeFailuresAndAllowsOneTrialAfterTheCooldown() {
    EmbedCircuit circuit = new EmbedCircuit(3, 1_000);
    assertTrue(circuit.allow(0));
    circuit.failure(0);
    circuit.failure(0);
    assertTrue(circuit.allow(0));
    circuit.failure(0);
    assertFalse(circuit.allow(100));
    assertTrue(circuit.allow(1_000));
    circuit.failure(1_000);
    assertFalse(circuit.allow(1_100));
  }

  @Test
  void failedCallCountsTowardTheOpenCircuit() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/embed",
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    server.start();
    try {
      AtomicLong now = new AtomicLong(0);
      EmbedClient client = client(server, now);
      for (int i = 0; i < 3; i++) {
        assertThrows(ExecutionException.class, () -> client.embed("x").get());
      }
      ExecutionException open = assertThrows(ExecutionException.class, () -> client.embed("x").get());
      assertTrue(open.getCause().getMessage().contains("circuit open"));
    } finally {
      server.stop(0);
    }
  }

  private static EmbedClient client(HttpServer server, AtomicLong now) {
    int port = server.getAddress().getPort();
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    return new EmbedClient(
        http,
        URI.create("http://127.0.0.1:" + port + "/embed"),
        new EmbedCircuit(3, 5_000),
        now::get);
  }
}
