package com.sarna.rag.flink;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Splits markdown on headings and keeps a fenced block or a list inside one section. */
public final class MarkdownChunker {
  public record Section(String heading, String text) {}

  private enum Kind {
    HEADING,
    CODE,
    LIST,
    PARAGRAPH
  }

  private record Block(Kind kind, String text, String heading) {}

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(\\S.*)$");
  private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+\\S.*$");

  private MarkdownChunker() {}

  public static List<Section> chunk(String markdown) {
    if (markdown == null || markdown.isBlank()) {
      return List.of();
    }
    String normalized = markdown.replace("\r\n", "\n");
    List<Block> blocks = parse(normalized);
    List<Section> sections = new ArrayList<>();
    String heading = "";
    StringBuilder body = new StringBuilder();
    for (Block block : blocks) {
      if (block.kind == Kind.HEADING && body.length() > 0) {
        sections.add(section(heading, body));
        body = new StringBuilder();
      }
      if (block.kind == Kind.HEADING) {
        heading = block.heading;
      }
      if (body.length() > 0) {
        body.append("\n\n");
      }
      body.append(block.text.strip());
    }
    if (body.length() > 0) {
      sections.add(section(heading, body));
    }
    return List.copyOf(sections);
  }

  private static Section section(String heading, StringBuilder body) {
    return new Section(heading, body.toString().strip() + "\n");
  }

  private static List<Block> parse(String markdown) {
    String[] lines = markdown.split("\n", -1);
    int end = lines.length;
    if (end > 0 && lines[end - 1].isEmpty()) {
      end--;
    }
    List<Block> blocks = new ArrayList<>();
    int index = 0;
    while (index < end) {
      String line = lines[index];
      if (line.isBlank()) {
        index++;
        continue;
      }
      if (isFence(line)) {
        String marker = fenceMarker(line);
        int start = index;
        index++;
        while (index < end && !closesFence(lines[index], marker)) {
          index++;
        }
        if (index < end) {
          index++;
        }
        blocks.add(new Block(Kind.CODE, join(lines, start, index), ""));
        continue;
      }
      String heading = headingText(line);
      if (heading != null) {
        blocks.add(new Block(Kind.HEADING, line, heading));
        index++;
        continue;
      }
      if (isListItem(line)) {
        int start = index;
        index++;
        while (index < end && continuesList(lines, index, end)) {
          index++;
        }
        blocks.add(new Block(Kind.LIST, join(lines, start, index), ""));
        continue;
      }
      int start = index;
      index++;
      while (index < end
          && !lines[index].isBlank()
          && !isFence(lines[index])
          && headingText(lines[index]) == null
          && !isListItem(lines[index])) {
        index++;
      }
      blocks.add(new Block(Kind.PARAGRAPH, join(lines, start, index), ""));
    }
    return blocks;
  }

  private static boolean continuesList(String[] lines, int index, int end) {
    if (isListItem(lines[index]) || isListContinuation(lines[index])) {
      return true;
    }
    return lines[index].isBlank()
        && index + 1 < end
        && (isListItem(lines[index + 1]) || isListContinuation(lines[index + 1]));
  }

  private static boolean isFence(String line) {
    String trimmed = line.stripLeading();
    return trimmed.startsWith("```") || trimmed.startsWith("~~~");
  }

  private static String fenceMarker(String line) {
    String trimmed = line.stripLeading();
    char marker = trimmed.charAt(0);
    int count = 0;
    while (count < trimmed.length() && trimmed.charAt(count) == marker) {
      count++;
    }
    return String.valueOf(marker).repeat(count);
  }

  private static boolean closesFence(String line, String marker) {
    String trimmed = line.strip();
    return trimmed.startsWith(marker) && trimmed.substring(marker.length()).isBlank();
  }

  private static String headingText(String line) {
    var match = HEADING.matcher(line);
    if (!match.matches()) {
      return null;
    }
    return match.group(2).strip();
  }

  private static boolean isListItem(String line) {
    return LIST_ITEM.matcher(line).matches();
  }

  private static boolean isListContinuation(String line) {
    return !line.isBlank()
        && (line.startsWith(" ") || line.startsWith("\t"))
        && headingText(line) == null
        && !isFence(line);
  }

  private static String join(String[] lines, int start, int end) {
    StringBuilder text = new StringBuilder();
    for (int index = start; index < end; index++) {
      if (index > start) {
        text.append('\n');
      }
      text.append(lines[index]);
    }
    return text.toString();
  }
}
