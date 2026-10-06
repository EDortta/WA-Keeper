#!/usr/bin/env python3
import argparse
import csv
import hashlib
import json
import sqlite3
import statistics
from pathlib import Path

LABELS = {
    "BASE_INT8": "Whisper base INT8",
    "SMALL_INT8": "Whisper small INT8",
}
RATING_LABELS = {
    "INCOMPREHENSIBLE": "Incompreensível",
    "ACCEPTABLE": "Aceitável",
    "GOOD": "Boa",
    "EXCELLENT": "Excelente",
}
RATINGS = list(RATING_LABELS)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("database")
    ap.add_argument("output")
    ap.add_argument("--device", default="desconhecido")
    ap.add_argument("--android", default="desconhecido")
    ap.add_argument("--app-version", default="desconhecida")
    args = ap.parse_args()

    out = Path(args.output)
    out.mkdir(parents=True, exist_ok=True)

    conn = sqlite3.connect(args.database)
    conn.row_factory = sqlite3.Row
    if not conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='transcription_runs'"
    ).fetchone():
        raise SystemExit("Tabela transcription_runs ausente.")

    rows = conn.execute(
        """SELECT id, notificationId, method, startedAt, elapsedMs, inferenceMs,
                  audioDurationMs, transcriptChars, status, error, rating, ratedAt
           FROM transcription_runs ORDER BY id"""
    ).fetchall()

    safe = []
    for row in rows:
        item = dict(row)
        raw_id = item.pop("notificationId")
        item["audio"] = hashlib.sha256(
            ("wa-keeper-asr:" + str(raw_id)).encode()
        ).hexdigest()[:12]
        item["rtf"] = (
            item["inferenceMs"] / item["audioDurationMs"]
            if item["audioDurationMs"] else None
        )
        safe.append(item)

    fields = [
        "id", "audio", "method", "startedAt", "elapsedMs", "inferenceMs",
        "audioDurationMs", "rtf", "transcriptChars", "status", "error",
        "rating", "ratedAt"
    ]
    with (out / "runs.tsv").open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fields, delimiter="\t")
        writer.writeheader()
        for row in safe:
            x = dict(row)
            x["rtf"] = "" if x["rtf"] is None else f"{x['rtf']:.4f}"
            writer.writerow(x)

    summary = {}
    for method in sorted({row["method"] for row in safe}):
        cur = [row for row in safe if row["method"] == method]
        done = [row for row in cur if row["status"] == "DONE"]
        rated = [row for row in done if row["rating"]]
        rtfs = [row["rtf"] for row in done if row["rtf"] is not None]
        summary[method] = {
            "runs": len(cur),
            "done": len(done),
            "errors": len(cur) - len(done),
            "rated": len(rated),
            "ratings": {
                rating: sum(1 for row in rated if row["rating"] == rating)
                for rating in RATINGS
            },
            "good_or_excellent": sum(
                1 for row in rated if row["rating"] in ("GOOD", "EXCELLENT")
            ),
            "mean_elapsed_ms": statistics.mean(
                row["elapsedMs"] for row in done
            ) if done else None,
            "mean_inference_ms": statistics.mean(
                row["inferenceMs"] for row in done
            ) if done else None,
            "mean_rtf": statistics.mean(rtfs) if rtfs else None,
        }

    by_audio = {}
    for row in safe:
        by_audio.setdefault(row["audio"], []).append(row)

    pairs = []
    for audio, cur in by_audio.items():
        methods = {row["method"] for row in cur if row["status"] == "DONE"}
        if len(methods) < 2:
            continue
        pairs.append({
            "audio": audio,
            "runs": [
                {
                    "method": row["method"],
                    "rating": row["rating"],
                    "elapsedMs": row["elapsedMs"],
                    "inferenceMs": row["inferenceMs"],
                    "audioDurationMs": row["audioDurationMs"],
                    "rtf": row["rtf"],
                }
                for row in cur if row["status"] == "DONE"
            ],
        })

    metrics = {
        "device": args.device,
        "android": args.android,
        "appVersion": args.app_version,
        "privacy": "Sem áudio, remetente, texto ou transcrição. notificationId anonimizado.",
        "summary": summary,
        "pairedAudios": pairs,
    }
    (out / "metrics.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    lines = [
        "# Evidência de uso ASR",
        "",
        f"- Aparelho: {args.device}",
        f"- Android: {args.android}",
        f"- WA-Keeper: {args.app_version}",
        "- Privacidade: nenhum áudio, remetente, texto ou transcrição foi exportado.",
        "",
        "## Resumo por método",
        "",
        "| Método | Execuções | Avaliadas | Incomp. | Aceitável | Boa | Excelente | Boa+Excelente | RTF médio |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for method, stats in summary.items():
        ratings = stats["ratings"]
        quality = (
            f"{100.0 * stats['good_or_excellent'] / stats['rated']:.1f}%"
            if stats["rated"] else "—"
        )
        rtf = "—" if stats["mean_rtf"] is None else f"{stats['mean_rtf']:.2f}x"
        lines.append(
            f"| {LABELS.get(method, method)} | {stats['runs']} | {stats['rated']} | "
            f"{ratings['INCOMPREHENSIBLE']} | {ratings['ACCEPTABLE']} | "
            f"{ratings['GOOD']} | {ratings['EXCELLENT']} | {quality} | {rtf} |"
        )

    lines += ["", "## Comparações no mesmo áudio", ""]
    if not pairs:
        lines.append("Ainda não há um mesmo áudio transcrito por mais de um método.")
    else:
        for pair in pairs:
            lines.append(f"- Áudio {pair['audio']}")
            for run in pair["runs"]:
                rating = RATING_LABELS.get(
                    run["rating"], run["rating"] or "sem avaliação"
                )
                rtf = "—" if run["rtf"] is None else f"{run['rtf']:.2f}x"
                lines.append(
                    f"  - {LABELS.get(run['method'], run['method'])}: "
                    f"{rating}, RTF {rtf}, {run['elapsedMs'] / 1000.0:.1f}s totais"
                )

    (out / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    conn.close()

if __name__ == "__main__":
    main()
