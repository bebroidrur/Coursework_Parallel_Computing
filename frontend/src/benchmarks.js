export function parseBenchmarkCsv(csv) {
  const lines = csv.trim().split(/\r?\n/).filter(Boolean);
  const [, ...rows] = lines;
  return rows.map(line => { const [mode, threads, iteration, milliseconds] = line.split(","); return { mode, threads: Number(threads), iteration: Number(iteration), milliseconds: Number(milliseconds) }; });
}

export function summarizeBenchmarks(rows) {
  const groups = new Map();
  for (const row of rows) { const key = `${row.mode}:${row.threads}`; const group = groups.get(key) || { mode: row.mode, threads: row.threads, values: [] }; group.values.push(row.milliseconds); groups.set(key, group); }
  return [...groups.values()].map(group => ({ mode: group.mode, threads: group.threads, average: group.values.reduce((a, b) => a + b, 0) / group.values.length, samples: group.values.length })).sort((a, b) => a.threads - b.threads || a.mode.localeCompare(b.mode));
}
