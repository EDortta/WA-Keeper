#!/usr/bin/env python3
import argparse
import json
import time
from pathlib import Path

from faster_whisper import WhisperModel


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("audio", type=Path)
    parser.add_argument("--model", default="small")
    parser.add_argument("--language", default=None)
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--compute-type", default="int8")
    parser.add_argument("--beam-size", type=int, default=5)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    language = args.language
    if language and language.lower() == "auto":
        language = None

    started = time.monotonic()
    model = WhisperModel(
        args.model,
        device=args.device,
        compute_type=args.compute_type,
    )
    segments, info = model.transcribe(
        str(args.audio),
        language=language,
        beam_size=args.beam_size,
        vad_filter=True,
    )

    texts = []
    for segment in segments:
        text = segment.text.strip()
        if text:
            texts.append(text)

    result = {
        "ok": True,
        "model": args.model,
        "language": getattr(info, "language", None) or language or "unknown",
        "languageProbability": getattr(info, "language_probability", None),
        "duration": float(getattr(info, "duration", 0.0) or 0.0),
        "elapsedMs": int((time.monotonic() - started) * 1000),
        "text": " ".join(texts).strip(),
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
