"""Generate a Markdown benchmark summary from JSON result files.

Reads benchmark-cases.tsv (label|path pairs) and prints a Markdown summary
to stdout, suitable for appending to $GITHUB_STEP_SUMMARY.
"""
import json
import os
import sys

columns = []
with open("benchmark-cases.tsv", "r", encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        label, path = line.split("|", 1)
        columns.append((label, path))

data = {}
for label, path in columns:
    try:
        if os.path.getsize(path) == 0:
            raise ValueError("empty file")
        with open(path, "r", encoding="utf-8") as f:
            doc = json.load(f)
        rows = {r["mode"]: r for r in doc["results"]}
        data[label] = {"doc": doc, "rows": rows}
    except Exception as e:
        print(f"<!-- WARNING: {path}: {e} -->", file=sys.stderr)
        data[label] = {
            "doc": {
                "input": path,
                "originalSize": 0,
                "bestMode": "none",
                "bestReductionPercent": 0.0,
                "bestTimeSeconds": 0,
                "results": [],
            },
            "rows": {},
        }

mode_order = []
seen_modes = set()
for label, _ in columns:
    for row in data[label]["doc"].get("results", []):
        mode = row.get("mode")
        if mode and mode not in seen_modes:
            seen_modes.add(mode)
            mode_order.append(mode)

print("## Benchmark Results\n")
for label, _ in columns:
    doc = data[label]["doc"]
    size = doc["originalSize"]
    size_mb = size / (1024 * 1024)
    bm = doc["bestMode"]
    if bm == "none":
        best_str = f"{size_mb:.3f} MB, best-size: all failed"
    else:
        pct = doc["bestReductionPercent"]
        reduced_mb = (size * pct / 100.0) / (1024 * 1024)
        t = doc["bestTimeSeconds"]
        best_str = f"{size_mb:.3f} MB, best-size: {bm} ({pct:.1f}%, reduced {reduced_mb:.3f} MB), mode-time {t}s"
    print(f"- **{label}**: {best_str}")
print()

col_labels = [label for label, _ in columns]
header = "| mode | " + " | ".join(col_labels) + " |"
sep = "| --- | " + " | ".join(["---:"] * len(columns)) + " |"

print("### Reduction (%)\n")
print(header)
print(sep)
mode_order_with_none = ["none"] + mode_order
for mode in mode_order_with_none:
    vals = []
    for label, _ in columns:
        if mode == "none":
            vals.append("0.0")
        else:
            row = data[label]["rows"].get(mode)
            if row is None or row.get("failed"):
                vals.append("--")
            else:
                vals.append(f"{row['savedPercent']:.1f}")
    print("| " + mode + " | " + " | ".join(vals) + " |")

print()
print("### Reduced Size (MB)\n")
print(header)
print(sep)
for mode in mode_order_with_none:
    vals = []
    for label, _ in columns:
        if mode == "none":
            vals.append(f"{data[label]['doc']['originalSize'] / (1024 * 1024):.3f}")
        else:
            row = data[label]["rows"].get(mode)
            if row is None or row.get("failed"):
                vals.append("--")
            else:
                vals.append(f"{row['sizeBytes'] / (1024 * 1024):.3f}")
    print("| " + mode + " | " + " | ".join(vals) + " |")

print()
print("### Time (s)\n")
print(header)
print(sep)
for mode in mode_order:
    vals = []
    for label, _ in columns:
        row = data[label]["rows"].get(mode)
        if row is None or row.get("failed"):
            vals.append("--")
        else:
            vals.append(f"{row['elapsedSeconds']:.1f}")
    print("| " + mode + " | " + " | ".join(vals) + " |")
