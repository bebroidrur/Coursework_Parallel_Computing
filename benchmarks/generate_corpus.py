#!/usr/bin/env python3
"""Generate an opt-in, deterministic UTF-8 text corpus for scale experiments.

The command writes one .txt file per document and never runs automatically as
part of Maven, Docker, or the application. Sizes use binary units: 100M is
100 MiB and 1G is 1 GiB. Use --dry-run to inspect the plan without writing.
"""
from __future__ import annotations

import argparse
import math
from pathlib import Path

UNITS = {"K": 1024, "M": 1024**2, "G": 1024**3}
TOPICS = (
    ("parallel computing", "threads, workers, synchronization, scheduling, speedup"),
    ("information retrieval", "documents, terms, ranking, relevance, postings"),
    ("inverted indexes", "buckets, collisions, vocabulary, lookup, searching"),
    ("distributed systems", "services, messages, latency, throughput, reliability"),
    ("machine learning", "models, features, training, inference, evaluation"),
    ("language processing", "tokens, normalization, Unicode, grammar, semantics"),
    ("software testing", "fixtures, assertions, regression, coverage, failures"),
    ("storage systems", "files, blocks, caching, sequential access, persistence"),
    ("network protocols", "clients, requests, responses, sockets, timeouts"),
    ("performance analysis", "workload, warmup, measurements, median, efficiency"),
)


def parse_size(value: str) -> int:
    text = value.strip().upper()
    for suffix, multiplier in UNITS.items():
        if text.endswith(suffix):
            try:
                amount = float(text[: -len(suffix)])
            except ValueError as error:
                raise argparse.ArgumentTypeError(f"invalid size: {value}") from error
            if amount <= 0:
                raise argparse.ArgumentTypeError("size must be positive")
            return math.ceil(amount * multiplier)
    raise argparse.ArgumentTypeError("size must use K, M or G suffix, for example 100M or 2G")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="new directory for generated .txt files")
    parser.add_argument("--documents", type=int, default=10_000, help="number of text documents (default: 10000)")
    parser.add_argument("--size", type=parse_size, required=True, metavar="SIZE",
                        help="minimum total size, e.g. 100M, 500M, 1G or 2G")
    parser.add_argument("--dry-run", action="store_true", help="show the plan without creating files")
    return parser.parse_args()


def paragraph(document_number: int) -> bytes:
    topic, terms = TOPICS[document_number % len(TOPICS)]
    text = (
        f"Document {document_number} is a technical report about {topic}. "
        f"The study discusses {terms}. "
        "The text is intentionally repetitive so that an inverted index can "
        "measure term frequency, postings, collisions, ranking, and concurrent access. "
        f"This document belongs to the {topic} collection and uses its vocabulary consistently.\n\n"
    )
    return text.encode("utf-8")


def generate(output: Path, documents: int, target_bytes: int) -> tuple[int, int]:
    if output.exists() and any(output.iterdir()):
        raise RuntimeError(f"output directory is not empty: {output}; choose a new directory")
    output.mkdir(parents=True, exist_ok=True)
    per_document = math.ceil(target_bytes / documents)
    actual = 0
    for number in range(documents):
        path = output / f"doc-{number + 1:05d}.txt"
        block = paragraph(number)
        written = 0
        with path.open("wb") as stream:
            while written < per_document:
                size = min(len(block), per_document - written)
                stream.write(block[:size])
                written += size
        actual += written
    (output / "generation-manifest.txt").write_text(
        f"documents={documents}\nrequested_bytes={target_bytes}\n"
        f"actual_text_bytes={actual}\nbytes_per_document_at_least={per_document}\n"
        "encoding=UTF-8\nfile_pattern=doc-*.txt\n",
        encoding="utf-8",
    )
    return documents, actual


def main() -> None:
    args = arguments()
    if args.documents < 1:
        raise SystemExit("--documents must be positive")
    per_document = math.ceil(args.size / args.documents)
    if args.dry_run:
        print(f"output={args.output}")
        print(f"documents={args.documents}")
        print(f"minimum_text_bytes={args.size}")
        print(f"minimum_bytes_per_document={per_document}")
        return
    count, actual = generate(args.output, args.documents, args.size)
    print(f"generated_documents={count}")
    print(f"actual_text_bytes={actual}")
    print(f"output={args.output}")


if __name__ == "__main__":
    main()
