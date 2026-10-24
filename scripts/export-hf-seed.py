#!/usr/bin/env python3
"""Export a small HuggingFace split into markdown files.

Requires the datasets package: pip install datasets
"""

import argparse
import os
import warnings

from seedutil import clear_markdown

warnings.filterwarnings("ignore", module="urllib3")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", default="ag_news")
    parser.add_argument("--output-dir", default="testdata/hf")
    args = parser.parse_args()

    try:
        from datasets import load_dataset
    except ImportError as err:
        raise SystemExit("pip install datasets") from err

    os.makedirs(args.output_dir, exist_ok=True)
    dataset = load_dataset(args.dataset, split="train", streaming=True)
    clear_markdown(args.output_dir)
    count = 0
    for item in dataset.take(40):
        count += 1
        text = item.get("text") or item.get("description") or ""
        title = item.get("title") or f"Document {count}"
        path = os.path.join(args.output_dir, f"hf_doc_{count:03d}.md")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(f'---\ntitle: "{title}"\ntenant_id: "tenant-alpha"\n---\n\n# {title}\n\n{text}\n')
    print(f"wrote {count} HuggingFace documents to {args.output_dir}", flush=True)
    # The datasets client leaves background workers behind, so a normal return
    # keeps this command running after the files are written.
    os._exit(0)


if __name__ == "__main__":
    main()
