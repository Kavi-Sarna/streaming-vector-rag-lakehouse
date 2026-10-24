"""Helpers shared by the seed exporters."""

import os


def clear_markdown(directory):
    for name in os.listdir(directory):
        if name.endswith(".md"):
            os.remove(os.path.join(directory, name))
