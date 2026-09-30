#!/usr/bin/env python3
import argparse
import json
import statistics
from pathlib import Path

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--device", required=True)
    args = ap.parse_args()

    root = Path(args.root)
    rows = []
    with open(root / "runs.tsv", encoding="utf-8") as f:
        next(f)
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) != 9:
                continue
            round_no, order_no, variant, duration, elapsed_ms, temp_before, temp_after, result_json, transcript = p
            rows.append({
                "round": int(round_no),
                "order": int(order_no),
                "variant": variant,
                "duration": float(duration),
                "elapsed": float(elapsed_ms) / 1000.0,
                "temp_before": None if not temp_before else float(temp_before),
                "temp_after": None if not temp_after else float(temp_after),
            })

    if not rows:
        raise SystemExit("Nenhum resultado para analisar.")

    variants = sorted({r["variant"] for r in rows})
    original_duration = max(r["duration"] for r in rows if r["variant"] == "original")
    stats = {}
    for variant in variants:
        cur = [r for r in rows if r["variant"] == variant]
        elapsed = [r["elapsed"] for r in cur]
        temps = [r["temp_after"] for r in cur if r["temp_after"] is not None]
        stats[variant] = {
            "runs": len(cur),
            "mean_elapsed": statistics.mean(elapsed),
            "median_elapsed": statistics.median(elapsed),
            "min_elapsed": min(elapsed),
            "max_elapsed": max(elapsed),
            "mean_rtf_vs_original": statistics.mean([x / original_duration for x in elapsed]),
            "duration_ratio": cur[0]["duration"] / original_duration,
            "mean_temp_after_c": statistics.mean(temps) if temps else None,
        }

    recommended = min(variants, key=lambda v: (stats[v]["median_elapsed"], stats[v]["mean_elapsed"]))
    labels = {
        "original": "Original",
        "silence": "Silêncio removido",
        "speed115": "1,15x",
        "speed125": "1,25x",
        "silence_speed115": "Silêncio removido + 1,15x",
    }

    lines = [
        "# Benchmark térmico ASR — desempenho",
        "",
        f"- Android: {args.device}",
        "- Modelo: sherpa-onnx Whisper small INT8.",
        "- Uma amostra longa, 3 rodadas, ordem aleatória por rodada.",
        "- Há intervalo de resfriamento entre execuções.",
        "",
        "## Resumo",
        "",
        "| Variante | Rodadas | Duração | Mediana | Média | Melhor | Pior | RTF vs original | Temp. média após |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for variant in variants:
        st = stats[variant]
        temp = "—" if st["mean_temp_after_c"] is None else f'{st["mean_temp_after_c"]:.1f} °C'
        lines.append(
            f'| {labels.get(variant, variant)} | {st["runs"]} | {st["duration_ratio"]:.1%} | '
            f'{st["median_elapsed"]:.1f}s | {st["mean_elapsed"]:.1f}s | '
            f'{st["min_elapsed"]:.1f}s | {st["max_elapsed"]:.1f}s | '
            f'{st["mean_rtf_vs_original"]:.2f}x | {temp} |'
        )

    lines += ["", "## Recomendação automática", "", labels.get(recommended, recommended), "", "## Execuções", "",
              "| Rodada | Ordem | Variante | Duração | Tempo | Temp. antes | Temp. depois |",
              "|---:|---:|---|---:|---:|---:|---:|"]
    for r in sorted(rows, key=lambda x: (x["round"], x["order"])):
        tb = "—" if r["temp_before"] is None else f'{r["temp_before"]:.1f} °C'
        ta = "—" if r["temp_after"] is None else f'{r["temp_after"]:.1f} °C'
        lines.append(
            f'| {r["round"]} | {r["order"]} | {labels.get(r["variant"], r["variant"])} | '
            f'{r["duration"]:.1f}s | {r["elapsed"]:.1f}s | {tb} | {ta} |'
        )

    (root / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (root / "metrics.json").write_text(json.dumps({"stats": stats, "recommended": recommended}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(recommended)

if __name__ == "__main__":
    main()
