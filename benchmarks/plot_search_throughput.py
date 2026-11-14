"""Create a dependency-free SVG for the search throughput benchmark."""
import csv
from pathlib import Path

root = Path(__file__).resolve().parent / "results/2026-11-14/search-throughput"
rows = list(csv.DictReader((root / "summary.csv").open(encoding="utf-8")))
clients = [int(row["clients"]) for row in rows]
panels = [
    ("Keyword search throughput (requests/s)", "mean_throughput_rps", "#2563eb"),
    ("Keyword search latency (ms)", "mean_latency_ms", "#dc2626"),
]
width, height = 1000, 560
svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">']
svg += ['<rect width="100%" height="100%" fill="#fff"/>',
        '<style>text{font-family:Arial,sans-serif;fill:#172033}.axis{stroke:#9aa5b1}.line{fill:none;stroke-width:3}.legend{font-size:12px}</style>']

def points(field, x0, y0, pw, ph):
    values = [float(row[field]) for row in rows]
    scale = max(values) * 1.12
    return [(x0 + i * pw / (len(values) - 1), y0 + ph - value / scale * (ph - 30)) for i, value in enumerate(values)]

for panel, (title, field, color) in enumerate(panels):
    x0, y0, pw, ph = 60 + panel * 470, 60, 380, 350
    svg.append(f'<text x="{x0}" y="30" font-size="17" font-weight="bold">{title}</text>')
    svg.append(f'<line class="axis" x1="{x0}" y1="{y0}" x2="{x0}" y2="{y0+ph}"/><line class="axis" x1="{x0}" y1="{y0+ph}" x2="{x0+pw}" y2="{y0+ph}"/>')
    pts = points(field, x0, y0, pw, ph)
    svg.append(f'<polyline class="line" stroke="{color}" points="' + ' '.join(f'{x:.1f},{y:.1f}' for x,y in pts) + '"/>')
    for (x, y), client in zip(pts, clients):
        svg.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="4" fill="{color}"/>')
        svg.append(f'<text x="{x-9:.1f}" y="{y-10:.1f}" class="legend">{float(rows[clients.index(client)][field]):.1f}</text>')
        svg.append(f'<text x="{x-8:.1f}" y="{y0+ph+22}" class="legend">{client}</text>')
    svg.append(f'<text x="{x0+pw/2-25}" y="{y0+ph+45}" class="legend">clients</text>')
svg.append('<text x="60" y="500" class="legend">Means over five repeats; latency panel shows mean request latency. See summary.csv for median, p95 and p99.</text>')
svg.append('</svg>')
(root / "throughput-latency.svg").write_text("\n".join(svg), encoding="utf-8")
print(root / "throughput-latency.svg")
