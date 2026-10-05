#!/usr/bin/env python3
import argparse
import json
import statistics
from pathlib import Path

LABELS = {
    "original": "Original",
    "silence": "Silêncio removido",
    "speed115": "1,15x",
    "speed125": "1,25x",
    "silence_speed115": "Silêncio removido + 1,15x",
    "base": "Whisper base INT8",
    "small": "Whisper small INT8",
}


def parse_float(value):
    return float(value.strip().replace(",", "."))


def parse_optional_float(value):
    value = value.strip()
    return None if not value else parse_float(value)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--device", required=True)
    args = ap.parse_args()

    root = Path(args.root)
    rows = []
    with open(root / "runs.tsv", encoding="utf-8") as fh:
        next(fh)
        for line in fh:
            p = line.rstrip("\n").split("\t")
            if len(p) == 7:
                round_no, order_no, variant, duration, elapsed_ms, result_json, transcript = p
                rows.append({
                    "round": int(round_no),
                    "order": int(order_no),
                    "variant": variant,
                    "duration": parse_float(duration),
                    "elapsed": parse_float(elapsed_ms) / 1000.0,
                    "temp_before": None,
                    "temp_after": None,
                    "thermal_before": None,
                    "thermal_after": None,
                })
            elif len(p) >= 12:
                rows.append({
                    "round": int(p[0]),
                    "order": int(p[1]),
                    "variant": p[2],
                    "duration": parse_float(p[3]),
                    "elapsed": parse_float(p[4]) / 1000.0,
                    "temp_before": parse_optional_float(p[5]),
                    "temp_after": parse_optional_float(p[6]),
                    "thermal_before": int(p[7]),
                    "thermal_after": int(p[8]),
                })

    if not rows:
        raise SystemExit("Nenhum resultado para analisar.")

    variants = sorted({r["variant"] for r in rows})
    original_rows = [r["duration"] for r in rows if r["variant"] == "original"]
    original_duration = max(original_rows) if original_rows else max(r["duration"] for r in rows)

    stats = {}
    for variant in variants:
        cur = [r for r in rows if r["variant"] == variant]
        elapsed = [r["elapsed"] for r in cur]
        temps_after = [r["temp_after"] for r in cur if r["temp_after"] is not None]
        stats[variant] = {
            "runs": len(cur),
            "mean_elapsed": statistics.mean(elapsed),
            "median_elapsed": statistics.median(elapsed),
            "min_elapsed": min(elapsed),
            "max_elapsed": max(elapsed),
            "mean_rtf_vs_original": statistics.mean([x / original_duration for x in elapsed]),
            "duration_ratio": cur[0]["duration"] / original_duration,
            "mean_temp_after_c": statistics.mean(temps_after) if temps_after else None,
            "max_thermal_after": max(
                (r["thermal_after"] for r in cur if r["thermal_after"] is not None),
                default=None,
            ),
        }

    recommended = min(
        variants,
        key=lambda v: (stats[v]["median_elapsed"], stats[v]["mean_elapsed"]),
    )

    lines = [
        "# Benchmark ASR — desempenho",
        "",
        f"- Android: {args.device}",
        "- Modelo: sherpa-onnx Whisper small INT8.",
        "- Execução autônoma no próprio celular; ADB não participa durante as rodadas.",
        "",
        "## Resumo",
        "",
        "| Variante | Rodadas | Duração | Mediana | Média | Melhor | Pior | RTF vs original | Temp. média após | Thermal máx. |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]

    for variant in variants:
        st = stats[variant]
        temp = "—" if st["mean_temp_after_c"] is None else f'{st["mean_temp_after_c"]:.1f} °C'
        thermal = "—" if st["max_thermal_after"] is None else str(st["max_thermal_after"])
        lines.append(
            f"| {LABELS.get(variant, variant)} | {st['runs']} | {st['duration_ratio']:.1%} | "
            f"{st['median_elapsed']:.1f}s | {st['mean_elapsed']:.1f}s | "
            f"{st['min_elapsed']:.1f}s | {st['max_elapsed']:.1f}s | "
            f"{st['mean_rtf_vs_original']:.2f}x | {temp} | {thermal} |"
        )

    lines += [
        "",
        "## Recomendação automática",
        "",
        LABELS.get(recommended, recommended),
        "",
        "## Execuções",
        "",
        "| Rodada | Ordem | Variante | Duração | Tempo | Temp. antes | Temp. depois | Thermal antes | Thermal depois |",
        "|---:|---:|---|---:|---:|---:|---:|---:|---:|",
    ]

    for row in sorted(rows, key=lambda x: (x["round"], x["order"])):
        tb = "—" if row["temp_before"] is None else f'{row["temp_before"]:.1f}'
        ta = "—" if row["temp_after"] is None else f'{row["temp_after"]:.1f}'
        thb = "—" if row["thermal_before"] is None else str(row["thermal_before"])
        tha = "—" if row["thermal_after"] is None else str(row["thermal_after"])
        lines.append(
            f"| {row['round']} | {row['order']} | {LABELS.get(row['variant'], row['variant'])} | "
            f"{row['duration']:.1f}s | {row['elapsed']:.1f}s | {tb} | {ta} | {thb} | {tha} |"
        )

    (root / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (root / "metrics.json").write_text(
        json.dumps({"stats": stats, "recommended": recommended}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(recommended)


if __name__ == "__main__":
    main()
