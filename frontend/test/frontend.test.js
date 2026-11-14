import test from "node:test";
import assert from "node:assert/strict";
import { resolveBackendUrl, toWebSocketUrl } from "../src/api.js";
import { parseBenchmarkCsv, summarizeBenchmarks } from "../src/benchmarks.js";

test("backend URL uses runtime config and query override", () => {
  assert.equal(resolveBackendUrl({ backendUrl: "http://backend:8080/" }, { search: "" }), "http://backend:8080");
  assert.equal(resolveBackendUrl({ backendUrl: "" }, { search: "?backend=https%3A%2F%2Fexample.test%2F" }), "https://example.test");
  assert.equal(resolveBackendUrl({ backendUrl: "http://backend:8080" }, { search: "?backend=http%3A%2F%2Foverride%3A8080" }), "http://override:8080");
});

test("WebSocket URL changes protocol and appends endpoint", () => {
  assert.equal(toWebSocketUrl("https://backend.example/", { href: "http://ui/", protocol: "http:", host: "ui" }), "wss://backend.example/ws/search");
});

test("benchmark parser preserves measured samples and averages groups", () => {
  const rows = parseBenchmarkCsv("mode,threads,iteration,milliseconds\nsequential,1,1,10\npool,2,1,5\npool,2,2,7");
  const summary = summarizeBenchmarks(rows);
  assert.equal(rows.length, 3);
  assert.equal(summary.find(row => row.mode === "pool" && row.threads === 2).average, 6);
  assert.equal(summary.find(row => row.mode === "pool" && row.threads === 2).samples, 2);
});
