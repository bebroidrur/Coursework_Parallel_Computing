"""Create a dependency-free SVG from extended-index-summary.csv."""
import csv
from pathlib import Path

ROOT = Path(__file__).resolve().parent
source = ROOT / "results/2026-11-14/extended-index-summary.csv"
target = ROOT / "results/2026-11-14/extended-performance.svg"
rows = list(csv.DictReader(source.open(encoding="utf-8")))
sizes = sorted({int(r["documents"]) for r in rows})
threads = [1, 2, 4, 8]
width, height = 1200, 760
panels = [("Mean build time (ms)", "mean_ms"), ("Speedup", "speedup"), ("Efficiency", "efficiency")]

def value(size, mode, n, field):
    row = next(r for r in rows if int(r["documents"]) == size and r["mode"] == mode and int(r["threads"]) == n)
    return float(row[field])

def esc(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">']
svg.append('<rect width="100%" height="100%" fill="#fff"/>')
svg.append('<style>text{font-family:Arial,sans-serif;fill:#172033} .axis{stroke:#9aa5b1} .line{fill:none;stroke-width:3} .legend{font-size:13px}</style>')
for p, (title, field) in enumerate(panels):
    x0, y0, pw, ph = 40 + p * 390, 60, 330, 580
    vals = [value(s, "pool", n, field) for s in sizes for n in threads]
    vmax = max(vals) * 1.12
    svg.append(f'<text x="{x0}" y="30" font-size="18" font-weight="bold">{esc(title)}</text>')
    svg.append(f'<line class="axis" x1="{x0}" y1="{y0}" x2="{x0}" y2="{y0+ph}"/><line class="axis" x1="{x0}" y1="{y0+ph}" x2="{x0+pw}" y2="{y0+ph}"/>')
    colors = ["#2563eb", "#dc2626", "#059669", "#9333ea"]
    for si, size in enumerate(sizes):
        points = []
        for ti, n in enumerate(threads):
            val = value(size, "pool", n, field)
            x = x0 + 35 + ti * 82
            y = y0 + ph - (val / vmax) * (ph - 30)
            points.append((x, y))
            svg.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="3" fill="{colors[si]}"/>')
        svg.append(f'<polyline class="line" stroke="{colors[si]}" points="' + ' '.join(f'{x:.1f},{y:.1f}' for x,y in points) + '"/>')
        svg.append(f'<text x="{x0+pw-75}" y="{y0+18+si*17}" class="legend" fill="{colors[si]}">{size} docs</text>')
    for ti, n in enumerate(threads):
        svg.append(f'<text x="{x0+30+ti*82}" y="{y0+ph+22}" font-size="11">{n}</text>')
    svg.append(f'<text x="{x0+pw/2-25}" y="{y0+ph+45}" font-size="12">threads</text>')
svg.append('<text x="40" y="720" class="legend">Each line is a document count; values are means from five measured runs after two warmups. See CSV for exact data.</text>')
svg.append('</svg>')
target.write_text("\n".join(svg), encoding="utf-8")
print(target)
