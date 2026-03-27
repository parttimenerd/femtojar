#!/usr/bin/env python3
"""Compile and compare before/after bytecode examples across techniques and JDKs."""

from __future__ import annotations

import argparse
import difflib
import shutil
import subprocess
import tempfile
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


@dataclass
class Result:
    technique: str
    jdk_label: str
    status: str
    before_class_bytes: int = 0
    after_class_bytes: int = 0
    before_deflate_bytes: int = 0
    after_deflate_bytes: int = 0
    notes: str = ""


@dataclass
class Toolchain:
    label: str
    javac: str
    javap: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Compile guide technique before/after examples and print bytecode size deltas."
        )
    )
    parser.add_argument(
        "--base-dir",
        default=Path(__file__).resolve().parent,
        type=Path,
        help="Folder containing technique subfolders (default: script directory).",
    )
    parser.add_argument(
        "--technique",
        help="Run a single technique folder (for example: 02_string_switch_if).",
    )
    parser.add_argument(
        "--javac",
        action="append",
        default=[],
        help=(
            "JDK compiler command to use. Repeat for multiple JDKs. "
            "Format: label=command or command. Default: javac"
        ),
    )
    parser.add_argument(
        "--release",
        type=str,
        help="Optional --release value passed to javac (for example: 17 or 21).",
    )
    parser.add_argument(
        "--save-javap-diffs",
        action="store_true",
        help="Write javap unified diffs to each technique folder.",
    )
    parser.add_argument(
        "--output-markdown",
        type=Path,
        help=(
            "Optional output markdown file path. "
            "If set, writes a report table to this file as well as stdout."
        ),
    )
    return parser.parse_args()


def parse_toolchains(specs: list[str]) -> list[Toolchain]:
    if not specs:
        specs = ["default=javac"]

    toolchains: list[Toolchain] = []
    for spec in specs:
        if "=" in spec:
            label, javac = spec.split("=", 1)
        else:
            javac = spec
            label = Path(spec).name

        javap = guess_javap(javac)
        toolchains.append(Toolchain(label=label, javac=javac, javap=javap))
    return toolchains


def guess_javap(javac_cmd: str) -> str:
    javac_path = shutil.which(javac_cmd)
    if javac_path:
        javap_candidate = str(Path(javac_path).with_name("javap"))
        if Path(javap_candidate).exists():
            return javap_candidate
    return "javap"


def discover_techniques(base_dir: Path, selected: str | None) -> list[Path]:
    if selected:
        selected_dir = base_dir / selected
        if not selected_dir.is_dir():
            raise FileNotFoundError(f"Technique folder not found: {selected_dir}")
        return [selected_dir]

    techniques = [
        p
        for p in sorted(base_dir.iterdir())
        if p.is_dir() and (p / "Before.java").exists() and (p / "After.java").exists()
    ]
    return techniques


def compile_java(javac: str, source_file: Path, out_dir: Path, release: str | None) -> None:
    cmd = [javac]
    if release:
        cmd.extend(["--release", release])
    cmd.extend(["-d", str(out_dir), str(source_file)])
    subprocess.run(cmd, check=True, capture_output=True, text=True)


def collect_class_files(out_dir: Path) -> list[Path]:
    return sorted(p for p in out_dir.rglob("*.class") if p.is_file())


def total_size(files: Iterable[Path]) -> int:
    return sum(p.stat().st_size for p in files)


def deflate_size(files: Iterable[Path], out_dir: Path) -> int:
    compressor = zlib.compressobj(level=9, wbits=-15)
    total = 0
    for class_file in sorted(files, key=lambda p: str(p.relative_to(out_dir))):
        rel = str(class_file.relative_to(out_dir)).replace("\\", "/")
        total += len(compressor.compress(rel.encode("utf-8")))
        total += len(compressor.compress(b"\0"))
        total += len(compressor.compress(class_file.read_bytes()))
    total += len(compressor.flush())
    return total


def javap_code(javap: str, class_name: str, classpath: Path) -> str:
    cmd = [javap, "-classpath", str(classpath), "-c", "-p", class_name]
    proc = subprocess.run(cmd, check=True, capture_output=True, text=True)
    return proc.stdout


def format_delta(after: int, before: int) -> str:
    delta = after - before
    sign = "+" if delta > 0 else ""
    pct = (delta / before * 100.0) if before else 0.0
    return f"{sign}{delta} ({sign}{pct:.1f}%)"


def run_one(technique_dir: Path, toolchain: Toolchain, release: str | None, save_javap_diffs: bool) -> Result:
    with tempfile.TemporaryDirectory(prefix=f"tech-{technique_dir.name}-") as temp_root:
        root = Path(temp_root)
        before_out = root / "before"
        after_out = root / "after"
        before_out.mkdir()
        after_out.mkdir()

        try:
            compile_java(toolchain.javac, technique_dir / "Before.java", before_out, release)
            compile_java(toolchain.javac, technique_dir / "After.java", after_out, release)

            before_classes = collect_class_files(before_out)
            after_classes = collect_class_files(after_out)
            before_bytes = total_size(before_classes)
            after_bytes = total_size(after_classes)
            before_deflate = deflate_size(before_classes, before_out)
            after_deflate = deflate_size(after_classes, after_out)

            before_javap = javap_code(toolchain.javap, "Before", before_out)
            after_javap = javap_code(toolchain.javap, "After", after_out)

            if save_javap_diffs:
                diff_lines = difflib.unified_diff(
                    before_javap.splitlines(),
                    after_javap.splitlines(),
                    fromfile="Before.javap",
                    tofile="After.javap",
                    lineterm="",
                )
                diff_path = technique_dir / f"javap_diff_{toolchain.label}.diff"
                diff_path.write_text("\n".join(diff_lines) + "\n", encoding="utf-8")

            return Result(
                technique=technique_dir.name,
                jdk_label=toolchain.label,
                status="ok",
                before_class_bytes=before_bytes,
                after_class_bytes=after_bytes,
                before_deflate_bytes=before_deflate,
                after_deflate_bytes=after_deflate,
            )
        except subprocess.CalledProcessError as exc:
            stderr = (exc.stderr or "").strip().replace("\n", " | ")
            return Result(
                technique=technique_dir.name,
                jdk_label=toolchain.label,
                status="failed",
                notes=stderr[:220],
            )


def build_table(results: list[Result]) -> str:
    header = [
        "Technique",
        "JDK",
        "Class Before",
        "Class After",
        "Class Delta",
        "Deflate Before",
        "Deflate After",
        "Deflate Delta",
        "Status",
    ]

    rows: list[list[str]] = []

    ok_results = [r for r in results if r.status == "ok"]
    for row in results:
        if row.status == "ok":
            rows.append([
                row.technique,
                row.jdk_label,
                str(row.before_class_bytes),
                str(row.after_class_bytes),
                format_delta(row.after_class_bytes, row.before_class_bytes),
                str(row.before_deflate_bytes),
                str(row.after_deflate_bytes),
                format_delta(row.after_deflate_bytes, row.before_deflate_bytes),
                row.status,
            ])
        else:
            rows.append([
                row.technique,
                row.jdk_label,
                "-",
                "-",
                "-",
                "-",
                "-",
                "-",
                f"{row.status}: {row.notes}",
            ])

    if ok_results:
        total_before_class = sum(r.before_class_bytes for r in ok_results)
        total_after_class = sum(r.after_class_bytes for r in ok_results)
        total_before_deflate = sum(r.before_deflate_bytes for r in ok_results)
        total_after_deflate = sum(r.after_deflate_bytes for r in ok_results)

        rows.append([
            "TOTAL(ok)",
            "all",
            str(total_before_class),
            str(total_after_class),
            format_delta(total_after_class, total_before_class),
            str(total_before_deflate),
            str(total_after_deflate),
            format_delta(total_after_deflate, total_before_deflate),
            "ok",
        ])

    right_align_cols = {2, 3, 4, 5, 6, 7}
    widths = [len(col) for col in header]
    for row in rows:
        for i, value in enumerate(row):
            widths[i] = max(widths[i], len(value))

    def format_cell(i: int, value: str) -> str:
        return value.rjust(widths[i]) if i in right_align_cols else value.ljust(widths[i])

    header_line = "| " + " | ".join(format_cell(i, value) for i, value in enumerate(header)) + " |"
    separator_parts = []
    for i, width in enumerate(widths):
        dash_count = max(3, width)
        if i in right_align_cols:
            separator_parts.append("-" * (dash_count - 1) + ":")
        else:
            separator_parts.append("-" * dash_count)
    separator_line = "| " + " | ".join(separator_parts) + " |"

    lines = [header_line, separator_line]
    for row in rows:
        lines.append("| " + " | ".join(format_cell(i, value) for i, value in enumerate(row)) + " |")

    return "\n".join(lines)


def print_table(results: list[Result]) -> None:
    print(build_table(results))


def write_markdown_report(
    output_file: Path,
    results: list[Result],
    release: str | None,
    toolchains: list[Toolchain],
    selected_technique: str | None,
) -> None:
    output_file.parent.mkdir(parents=True, exist_ok=True)
    table = build_table(results)

    release_text = release if release else "default"
    toolchain_text = ", ".join(f"{t.label}={t.javac}" for t in toolchains)
    technique_text = selected_technique if selected_technique else "all"

    content = (
        "# Bytecode Technique Snapshot\n\n"
        f"- Release: {release_text}\n"
        f"- Toolchains: {toolchain_text}\n"
        f"- Technique filter: {technique_text}\n\n"
        f"{table}\n"
    )
    output_file.write_text(content, encoding="utf-8")


def main() -> int:
    args = parse_args()
    base_dir = args.base_dir.resolve()

    try:
        techniques = discover_techniques(base_dir, args.technique)
    except FileNotFoundError as exc:
        print(str(exc))
        return 2

    toolchains = parse_toolchains(args.javac)
    results: list[Result] = []

    for toolchain in toolchains:
        for technique in techniques:
            result = run_one(
                technique_dir=technique,
                toolchain=toolchain,
                release=args.release,
                save_javap_diffs=args.save_javap_diffs,
            )
            results.append(result)

    print_table(results)
    if args.output_markdown:
        write_markdown_report(
            output_file=args.output_markdown.resolve(),
            results=results,
            release=args.release,
            toolchains=toolchains,
            selected_technique=args.technique,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
