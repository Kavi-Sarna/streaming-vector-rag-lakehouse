#!/usr/bin/env python3
"""Turn an arXiv Atom feed into markdown files."""

import argparse
import os
import xml.etree.ElementTree as ET

from seedutil import clear_markdown

NS = {"atom": "http://www.w3.org/2005/Atom"}


def text(entry, tag):
    node = entry.find(f"atom:{tag}", NS)
    if node is None or node.text is None:
        return ""
    return " ".join(node.text.split())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output-dir", default="testdata/arxiv")
    args = parser.parse_args()
    os.makedirs(args.output_dir, exist_ok=True)

    root = ET.parse(args.input).getroot()
    clear_markdown(args.output_dir)
    count = 0
    for entry in root.findall("atom:entry", NS):
        title = text(entry, "title") or f"Document {count + 1}"
        summary = text(entry, "summary")
        paper_id = text(entry, "id").split("/abs/")[-1] or f"{count + 1}"
        safe_id = paper_id.replace("/", "_").replace(".", "_")
        path = os.path.join(args.output_dir, f"arxiv_{safe_id}.md")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(
                f'---\ntitle: "{title}"\ntenant_id: "tenant-alpha"\narxiv_id: "{paper_id}"\n---\n\n'
                f"# {title}\n\n## Abstract\n\n{summary}\n"
            )
        count += 1
    print(f"wrote {count} arXiv abstracts to {args.output_dir}")


if __name__ == "__main__":
    main()
