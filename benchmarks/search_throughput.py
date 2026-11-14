#!/usr/bin/env python3
"""Measure POST /api/search throughput with a single immutable index.

The benchmark uses only the standard library. Run it from the repository root
with a running backend, or pass --start-server after building the backend jar.
"""
from __future__ import annotations

import argparse
import csv
import http.client
import json
import os
import statistics
import subprocess
import threading
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

QUERIES = {
    "java": {"03-java.txt"},
    "threads": {"03-java.txt"},
    "індекс": {"02-search.txt"},
    "пошук": {"04-llm.txt"},
    "відсутній": set(),
}
CLIENT_COUNTS = (1, 2, 4, 8, 16)
REPEATS = 5
REQUESTS_PER_CLIENT = 100
WARMUP_REQUESTS = 100


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:18081")
    parser.add_argument("--output", default="benchmarks/results/2026-11-14/search-throughput")
    parser.add_argument("--requests-per-client", type=int, default=REQUESTS_PER_CLIENT)
    parser.add_argument("--repeats", type=int, default=REPEATS)
    parser.add_argument("--start-server", action="store_true")
    parser.add_argument("--server-jar", default="backend/target/backend-0.0.1-SNAPSHOT.jar")
    return parser.parse_args()


def endpoint(base_url: str) -> tuple[str, int]:
    parsed = urllib.parse.urlsplit(base_url)
    return parsed.hostname or "127.0.0.1", parsed.port or (443 if parsed.scheme == "https" else 80)


def request(base_url: str, path: str, body: dict, timeout: float = 30.0) -> tuple[int, dict, float, str]:
    host, port = endpoint(base_url)
    started = time.perf_counter()
    connection = http.client.HTTPConnection(host, port, timeout=timeout)
    try:
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        connection.request("POST", path, payload, {"Content-Type": "application/json", "Accept": "application/json"})
        response = connection.getresponse()
        raw = response.read()
        status = response.status
        try:
            decoded = json.loads(raw.decode("utf-8")) if raw else {}
        except (UnicodeDecodeError, json.JSONDecodeError):
            decoded = {}
        return status, decoded, (time.perf_counter() - started) * 1000, ""
    except Exception as error:  # measured as a failed request, never abort the run
        return 0, {}, (time.perf_counter() - started) * 1000, type(error).__name__
    finally:
        connection.close()


def correct(query: str, status: int, response: dict) -> bool:
    if status != 200 or response.get("status") != "ok":
        return False
    actual = {item.get("document_id") for item in response.get("results", [])}
    return actual == QUERIES[query] and response.get("total") == len(actual)


def warmup(base_url: str, count: int) -> None:
    for index in range(count):
        query = tuple(QUERIES)[index % len(QUERIES)]
        status, response, _, error = request(base_url, "/api/search", {"query": query, "mode": "keyword"})
        if not correct(query, status, response):
            raise RuntimeError(f"Warmup search returned an unexpected result: status={status}, error={error}, response={response}")


def measure_round(base_url: str, clients: int, requests_per_client: int, experiment: str,
                  repeat: int, raw_rows: list[dict]) -> dict:
    barrier = threading.Barrier(clients)

    def worker(worker_id: int) -> None:
        barrier.wait()
        for sequence in range(requests_per_client):
            query = tuple(QUERIES)[(worker_id * requests_per_client + sequence) % len(QUERIES)]
            status, response, latency, error = request(base_url, "/api/search",
                                                        {"query": query, "mode": "keyword"})
            is_correct = correct(query, status, response)
            raw_rows.append({
                "experiment": experiment,
                "clients": clients,
                "repeat": repeat,
                "worker": worker_id,
                "sequence": sequence,
                "query": query,
                "http_status": status,
                "success": status == 200,
                "correct": is_correct,
                "latency_ms": f"{latency:.3f}",
                "error": error,
            })

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=clients, thread_name_prefix="search-client") as pool:
        list(pool.map(worker, range(clients)))
    elapsed = time.perf_counter() - started
    rows = raw_rows[-clients * requests_per_client:]
    latencies = [float(row["latency_ms"]) for row in rows]
    successful = sum(row["success"] for row in rows)
    correct_count = sum(row["correct"] for row in rows)
    return {
        "experiment": experiment,
        "clients": clients,
        "repeat": repeat,
        "total_requests": len(rows),
        "successful": successful,
        "failed": len(rows) - successful,
        "correct": correct_count,
        "wall_seconds": elapsed,
        "requests_per_second": len(rows) / elapsed if elapsed else 0.0,
        "mean_latency_ms": statistics.fmean(latencies) if latencies else 0.0,
        "median_latency_ms": statistics.median(latencies) if latencies else 0.0,
        "p95_latency_ms": percentile(latencies, 0.95),
        "p99_latency_ms": percentile(latencies, 0.99),
        "success_rate": successful / len(rows) if rows else 0.0,
        "correctness_rate": correct_count / len(rows) if rows else 0.0,
    }


def percentile(values: list[float], quantile: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = max(1, int((len(ordered) * quantile) + 0.999999))
    return ordered[min(rank, len(ordered)) - 1]


def write_csv(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def summarize(measurements: list[dict]) -> list[dict]:
    summary = []
    for clients in CLIENT_COUNTS:
        rows = [row for row in measurements if row["clients"] == clients]
        def average(field: str) -> float:
            return statistics.fmean(float(row[field]) for row in rows)
        summary.append({
            "clients": clients,
            "repeats": len(rows),
            "requests_per_repeat": rows[0]["total_requests"],
            "total_requests": sum(row["total_requests"] for row in rows),
            "successful": sum(row["successful"] for row in rows),
            "failed": sum(row["failed"] for row in rows),
            "correct": sum(row["correct"] for row in rows),
            "mean_throughput_rps": average("requests_per_second"),
            "mean_latency_ms": average("mean_latency_ms"),
            "median_latency_ms": average("median_latency_ms"),
            "p95_latency_ms": average("p95_latency_ms"),
            "p99_latency_ms": average("p99_latency_ms"),
            "mean_wall_seconds": average("wall_seconds"),
            "success_rate": sum(row["successful"] for row in rows) / sum(row["total_requests"] for row in rows),
            "correctness_rate": sum(row["correct"] for row in rows) / sum(row["total_requests"] for row in rows),
        })
    return summary


def main() -> None:
    args = parse_args()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    process = None
    try:
        if args.start_server:
            process = subprocess.Popen([
                "java", "-Djava.io.tmpdir=backend/target", "-jar", args.server_jar,
                "--server.address=127.0.0.1", "--server.port=18081",
            ], stdout=(output / "server.log").open("w"), stderr=subprocess.STDOUT)
            for _ in range(100):
                status, _, _, _ = request(args.base_url, "/api/search", {"query": "java", "mode": "keyword"}, 1.0)
                if status in (200, 400):
                    break
                time.sleep(0.1)
        status, indexed, _, error = request(args.base_url, "/api/index", {"threads": 4})
        if status != 200:
            raise RuntimeError(f"Index request failed: status={status}, error={error}, response={indexed}")
        warmup(args.base_url, WARMUP_REQUESTS)

        raw_rows: list[dict] = []
        measurements = []
        for clients in CLIENT_COUNTS:
            for repeat in range(1, args.repeats + 1):
                measurements.append(measure_round(args.base_url, clients, args.requests_per_client,
                                                  "keyword-search", repeat, raw_rows))
        summary = summarize(measurements)
        write_csv(output / "raw-requests.csv", raw_rows)
        write_csv(output / "repeat-summary.csv", measurements)
        write_csv(output / "summary.csv", summary)
        (output / "environment.txt").write_text(
            "endpoint=" + args.base_url + "\n"
            + "mode=keyword\n"
            + "index_build_threads=4\n"
            + "queries=" + ",".join(QUERIES) + "\n"
            + f"clients={','.join(map(str, CLIENT_COUNTS))}\nrepeats={args.repeats}\n"
            + f"requestsPerClient={args.requests_per_client}\nwarmupRequests={WARMUP_REQUESTS}\n"
            + "throughput=total requests divided by wall-clock duration of each concurrent round\n"
            + "correctness=expected document IDs and total for every response\n"
            + "python=" + os.sys.version.replace("\n", " ") + "\n",
            encoding="utf-8",
        )
        print(json.dumps({"index": indexed, "summary": summary}, ensure_ascii=False, indent=2))
    finally:
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


if __name__ == "__main__":
    main()
