#!/usr/bin/env python3
import argparse
import json
import random
import re
import sqlite3
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


def select_from_db(args):
    con = sqlite3.connect(args.db)
    rows = con.execute(
        "SELECT id, sender, text, audioPath FROM notifications "
        "WHERE audioPath IS NOT NULL AND trim(audioPath) <> ''"
    ).fetchall()
    con.close()
    if len(rows) < 3:
        raise SystemExit(f"Há somente {len(rows)} áudio(s) salvo(s); preciso de pelo menos 3.")
    random.shuffle(rows)
    rows = rows[: min(args.pool, len(rows))]
    with open(args.out, "w", encoding="utf-8") as f:
        for row in rows:
            vals = [str(row[0]), row[1] or "", row[2] or "", row[3] or ""]
            vals = [v.replace("\t", " ").replace("\n", " ") for v in vals]
            f.write("\t".join(vals) + "\n")


def pick_lengths(args):
    rows = []
    with open(args.measured, encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t", 4)
            if len(p) == 5:
                rows.append((p[0], float(p[1]), p[2], p[3], p[4]))
    if len(rows) < 3:
        raise SystemExit("Menos de três áudios medidos.")
    rows.sort(key=lambda x: x[1])
    small = [r for r in rows if r[1] <= 15]
    medium = [r for r in rows if 15 < r[1] <= 60]
    long = [r for r in rows if r[1] > 60]
    if not (small and medium and long):
        n = len(rows)
        a = max(1, n // 3)
        b = max(a + 1, (2 * n) // 3)
        small = rows[:a]
        medium = rows[a:b] or rows[a : a + 1]
        long = rows[b:] or rows[-1:]
    chosen = [
        ("pequena", random.choice(small)),
        ("media", random.choice(medium)),
        ("longa", random.choice(long)),
    ]
    with open(args.out, "w", encoding="utf-8") as f:
        for label, row in chosen:
            f.write(
                "\t".join(
                    [
                        label,
                        row[0],
                        f"{row[1]:.3f}",
                        row[2].replace("\t", " "),
                        row[3].replace("\t", " "),
                        row[4],
                    ]
                )
                + "\n"
            )


def build_report(args):
    root = Path(args.root)
    models = args.models.split(",")
    samples = []
    with open(root / "samples.tsv", encoding="utf-8") as f:
        next(f)
        for line in f:
            label, ident, duration, sender, sample = line.rstrip("\n").split("\t")
            samples.append(
                dict(
                    label=label,
                    id=ident,
                    duration=float(duration),
                    sender=sender,
                    sample=sample,
                )
            )

    rows = []
    for model in models:
        for sample in samples:
            label = sample["label"]
            ref = (root / "reference" / label / "transcript.txt").read_text(
                encoding="utf-8"
            ).strip()
            data = json.loads(
                (root / "android" / model / f"{label}.json").read_text(
                    encoding="utf-8"
                )
            )
            hyp = data.get("text", "").strip()
            elapsed = float(data["elapsedMs"]) / 1000.0
            rows.append(
                dict(
                    model=model,
                    label=label,
                    wer=wer(ref, hyp),
                    cer=cer(ref, hyp),
                    elapsed=elapsed,
                    rtf=elapsed / max(0.001, sample["duration"]),
                    hyp=hyp,
                )
            )

    stats = {}
    for model in models:
        current = [r for r in rows if r["model"] == model]
        stats[model] = dict(
            wer=sum(r["wer"] for r in current) / len(current),
            cer=sum(r["cer"] for r in current) / len(current),
            rtf=sum(r["rtf"] for r in current) / len(current),
            elapsed=sum(r["elapsed"] for r in current),
        )

    best = min(models, key=lambda m: (stats[m]["wer"], stats[m]["rtf"]))
    limit = stats[best]["wer"] + 0.03
    near = [m for m in models if stats[m]["wer"] <= limit]
    recommended = min(near, key=lambda m: stats[m]["rtf"])

    lines = [
        "# Benchmark ASR WA-Keeper",
        "",
        f"- Android: {args.device}",
        "- Referência comparativa: faster-whisper small no devel3.",
        "- Android: sherpa-onnx + Whisper ONNX INT8.",
        "- WER/CER medem semelhança com a referência, não verdade absoluta.",
        "",
        "## Resumo",
        "",
        "| Modelo Android | WER médio | CER médio | RTF médio | Tempo total |",
        "|---|---:|---:|---:|---:|",
    ]
    for model in models:
        st = stats[model]
        lines.append(
            f"| {model} | {st['wer']:.1%} | {st['cer']:.1%} | "
            f"{st['rtf']:.2f}x | {st['elapsed']:.1f}s |"
        )
    lines += [
        "",
        "## Recomendação automática",
        "",
        f"{recommended}: WER médio {stats[recommended]['wer']:.1%}, "
        f"RTF {stats[recommended]['rtf']:.2f}x.",
        "",
        "Regra: prioriza qualidade; modelos até 3 pontos percentuais de WER "
        "do melhor competem por velocidade.",
        "",
        "## Amostras",
        "",
    ]
    for sample in samples:
        label = sample["label"]
        ref = (root / "reference" / label / "transcript.txt").read_text(
            encoding="utf-8"
        ).strip()
        lines += [
            f"### {label} — {sample['duration']:.1f}s",
            "",
            f"Remetente: {sample['sender']}",
            "",
            "**devel3 / faster-whisper small**",
            "",
            ref,
            "",
        ]
        for model in models:
            row = next(
                r
                for r in rows
                if r["model"] == model and r["label"] == label
            )
            lines += [
                f"**Android / sherpa-onnx Whisper {model}** "
                f"(WER {row['wer']:.1%}, RTF {row['rtf']:.2f}x)",
                "",
                row["hyp"],
                "",
            ]

    (root / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (root / "metrics.json").write_text(
        json.dumps(
            {"stats": stats, "recommended": recommended},
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    print(recommended)


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("select")
    p.add_argument("db")
    p.add_argument("out")
    p.add_argument("--pool", type=int, default=24)
    p.set_defaults(func=select_from_db)

    p = sub.add_parser("pick")
    p.add_argument("measured")
    p.add_argument("out")
    p.set_defaults(func=pick_lengths)

    p = sub.add_parser("report")
    p.add_argument("root")
    p.add_argument("--device", required=True)
    p.add_argument("--models", required=True)
    p.set_defaults(func=build_report)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
