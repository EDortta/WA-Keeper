#!/usr/bin/env python3
import argparse
import json
import re
import unicodedata
from pathlib import Path


def normalize(text):
    text = unicodedata.normalize("NFKD", text.lower())
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    return re.findall(r"[a-z0-9]+", text)


def distance(a, b):
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(cur[-1] + 1, prev[j] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def wer(ref, hyp):
    r, h = normalize(ref), normalize(hyp)
    return distance(r, h) / max(1, len(r))


def cer(ref, hyp):
    r = list(" ".join(normalize(ref)))
    h = list(" ".join(normalize(hyp)))
    return distance(r, h) / max(1, len(r))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--device", required=True)
    args = ap.parse_args()

    root = Path(args.root)
    variants = ["original", "silence", "speed115", "speed125", "silence_speed115"]

    samples = []
    with open(root / "samples.tsv", encoding="utf-8") as f:
        next(f)
        for line in f:
            label, ident, duration, sender, sample = line.rstrip("\n").split("\t")
            samples.append({
                "label": label,
                "id": ident,
                "duration": float(duration),
                "sender": sender,
                "sample": sample,
            })

    rows = []
    for sample in samples:
        ref = (root / "reference" / sample["label"] / "transcript.txt").read_text(
            encoding="utf-8"
        ).strip()
        for variant in variants:
            meta = json.loads(
                (root / "variants" / sample["label"] / f"{variant}.json").read_text(
                    encoding="utf-8"
                )
            )
            data = json.loads(
                (root / "android" / variant / f"{sample['label']}.json").read_text(
                    encoding="utf-8"
                )
            )
            hyp = data.get("text", "").strip()
            elapsed = float(data["elapsedMs"]) / 1000.0
            transformed = float(meta["duration"])
            original = sample["duration"]
            rows.append({
                "variant": variant,
                "label": sample["label"],
                "wer": wer(ref, hyp),
                "cer": cer(ref, hyp),
                "elapsed": elapsed,
                "transformed_duration": transformed,
                "original_duration": original,
                "rtf_transformed": elapsed / max(0.001, transformed),
                "rtf_original": elapsed / max(0.001, original),
                "duration_ratio": transformed / max(0.001, original),
                "hyp": hyp,
            })

    stats = {}
    for variant in variants:
        cur = [r for r in rows if r["variant"] == variant]
        stats[variant] = {
            "wer": sum(r["wer"] for r in cur) / len(cur),
            "cer": sum(r["cer"] for r in cur) / len(cur),
            "rtf_transformed": sum(r["rtf_transformed"] for r in cur) / len(cur),
            "rtf_original": sum(r["rtf_original"] for r in cur) / len(cur),
            "duration_ratio": sum(r["duration_ratio"] for r in cur) / len(cur),
            "elapsed": sum(r["elapsed"] for r in cur),
        }

    baseline = stats["original"]
    acceptable = [
        v for v in variants
        if stats[v]["wer"] <= baseline["wer"] + 0.03
    ]
    recommended = min(
        acceptable or ["original"],
        key=lambda v: (stats[v]["elapsed"], stats[v]["wer"])
    )

    labels = {
        "original": "Original",
        "silence": "Silêncio removido",
        "speed115": "1,15x",
        "speed125": "1,25x",
        "silence_speed115": "Silêncio removido + 1,15x",
    }

    lines = [
        "# Benchmark ASR — pré-processamento",
        "",
        f"- Android: {args.device}",
        "- Modelo Android: sherpa-onnx Whisper small INT8.",
        "- Referência: faster-whisper small no devel3 sobre o áudio original.",
        "- O objetivo é reduzir tempo sem piorar a qualidade em mais de 3 pontos percentuais de WER.",
        "",
        "## Resumo",
        "",
        "| Variante | WER médio | CER médio | Duração média | RTF vs áudio original | Tempo total |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for variant in variants:
        st = stats[variant]
        lines.append(
            f"| {labels[variant]} | {st['wer']:.1%} | {st['cer']:.1%} | "
            f"{st['duration_ratio']:.1%} | {st['rtf_original']:.2f}x | {st['elapsed']:.1f}s |"
        )

    lines += [
        "",
        "## Recomendação automática",
        "",
        f"{labels[recommended]}: WER médio {stats[recommended]['wer']:.1%}, "
        f"tempo total {stats[recommended]['elapsed']:.1f}s.",
        "",
        "## Amostras",
        "",
    ]

    for sample in samples:
        ref = (root / "reference" / sample["label"] / "transcript.txt").read_text(
            encoding="utf-8"
        ).strip()
        lines += [
            f"### {sample['label']} — {sample['duration']:.1f}s",
            "",
            f"Remetente: {sample['sender']}",
            "",
            "**Referência / devel3**",
            "",
            ref,
            "",
        ]
        for variant in variants:
            row = next(
                r for r in rows
                if r["variant"] == variant and r["label"] == sample["label"]
            )
            lines += [
                f"**{labels[variant]}** "
                f"(duração {row['transformed_duration']:.1f}s, "
                f"WER {row['wer']:.1%}, "
                f"tempo {row['elapsed']:.1f}s)",
                "",
                row["hyp"],
                "",
            ]

    (root / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (root / "metrics.json").write_text(
        json.dumps(
            {
                "stats": stats,
                "recommended": recommended,
                "baseline": "original",
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    print(recommended)


if __name__ == "__main__":
    main()
