package com.sarna.rag.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TitanEmbedderTest {
  @Test
  void requestsANormalized1024DimensionVector() {
    String json = TitanEmbedder.requestJson("a\"b");
    assertTrue(json.contains("\"dimensions\":1024"));
    assertTrue(json.contains("\"normalize\":true"));
    assertTrue(json.contains("\"inputText\":\"a\\\"b\""));
    assertEquals(TitanEmbedder.MODEL_ID, "amazon.titan-embed-text-v2:0");
  }

  @Test
  void readsTheEmbeddingArray() {
    float[] vector = TitanEmbedder.parseEmbedding("{\"embedding\":[0.25,-0.5],\"inputTextTokenCount\":2}");
    assertEquals(2, vector.length);
    assertEquals(0.25f, vector[0]);
    assertEquals(-0.5f, vector[1]);
  }

  @Test
  void rejectsAVectorThatIsNot1024Wide() {
    EmbedClient.EmbedException error =
        assertThrows(EmbedClient.EmbedException.class, () -> TitanEmbedder.requireWidth(new float[] {1f}));
    assertTrue(error.getMessage().contains("1"));
  }

  @Test
  void rejectsAnUnknownEmbedderName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> EmbedFunction.openEmbedder("titan", "http://127.0.0.1/embed", "us-east-1"));
  }
}
