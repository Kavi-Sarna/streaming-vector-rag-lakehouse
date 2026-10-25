package com.sarna.rag.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownChunkerTest {
  @Test
  void blankInputHasNoSections() {
    assertTrue(MarkdownChunker.chunk("").isEmpty());
    assertTrue(MarkdownChunker.chunk("  \n").isEmpty());
  }

  @Test
  void proseWithoutAHeadingIsOneSection() {
    List<MarkdownChunker.Section> sections = MarkdownChunker.chunk("alpha\n\nbeta\n");
    assertEquals(1, sections.size());
    assertEquals("", sections.get(0).heading());
    assertTrue(sections.get(0).text().contains("alpha"));
    assertTrue(sections.get(0).text().contains("beta"));
  }

  @Test
  void headingsStartSections() {
    List<MarkdownChunker.Section> sections =
        MarkdownChunker.chunk("# Title\n\nintro\n\n## Detail\n\nmore\n");
    assertEquals(2, sections.size());
    assertEquals("Title", sections.get(0).heading());
    assertTrue(sections.get(0).text().contains("intro"));
    assertEquals("Detail", sections.get(1).heading());
    assertTrue(sections.get(1).text().contains("more"));
  }

  @Test
  void aFenceThatLooksLikeAHeadingStaysInTheSection() {
    String markdown = "# Title\n\n```\n# not a heading\n```\n\n## Next\n\nafter\n";
    List<MarkdownChunker.Section> sections = MarkdownChunker.chunk(markdown);
    assertEquals(2, sections.size());
    assertEquals("Title", sections.get(0).heading());
    assertTrue(sections.get(0).text().contains("# not a heading"));
    assertEquals("Next", sections.get(1).heading());
  }

  @Test
  void aListStaysTogether() {
    String markdown = "# Title\n\n- one\n- two\n  continued\n\n- three\n\n## Next\n\ndone\n";
    List<MarkdownChunker.Section> sections = MarkdownChunker.chunk(markdown);
    assertEquals(2, sections.size());
    String first = sections.get(0).text();
    assertTrue(first.contains("- one"));
    assertTrue(first.contains("- two"));
    assertTrue(first.contains("continued"));
    assertTrue(first.contains("- three"));
    assertEquals("Next", sections.get(1).heading());
  }
}
