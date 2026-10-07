#!/usr/bin/env python3
from __future__ import annotations

import curses
import os
import re
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "publisher" / "features.properties"
BUILD_GRADLE = ROOT / "app" / "build.gradle"

TRUE = {"1", "true", "yes", "on"}


def load_lines() -> list[str]:
    return REGISTRY.read_text(encoding="utf-8").splitlines(keepends=True)


def parse(lines: list[str]) -> dict[str, str]:
    out: dict[str, str] = {}
    for raw in lines:
        line = raw.rstrip("\n")
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def bool_prop(props: dict[str, str], key: str) -> bool:
    return props.get(key, "false").strip().lower() in TRUE


def set_prop(lines: list[str], key: str, value: str) -> list[str]:
    pat = re.compile(rf"^(\s*{re.escape(key)}\s*=).*$")
    for i, raw in enumerate(lines):
        body = raw.rstrip("\n")
        m = pat.match(body)
        if m:
            suffix = "\n" if raw.endswith("\n") else ""
            lines[i] = f"{m.group(1)}{value}{suffix}"
            return lines
    if lines and not lines[-1].endswith("\n"):
        lines[-1] += "\n"
    lines.append(f"{key}={value}\n")
    return lines


def save(lines: list[str]) -> None:
    fd, tmp = tempfile.mkstemp(prefix="features.", suffix=".properties", dir=str(REGISTRY.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.writelines(lines)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, REGISTRY)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def features(props: dict[str, str]) -> list[str]:
    return [x.strip() for x in props.get("features", "").split(",") if x.strip()]


def current_branch() -> str:
    import subprocess
    try:
        cp = subprocess.run(
            ["git", "branch", "--show-current"],
            cwd=ROOT,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
        )
        return cp.stdout.strip() or "(detached)"
    except Exception:
        return "(desconhecida)"


def current_version() -> tuple[str, str]:
    try:
        text = BUILD_GRADLE.read_text(encoding="utf-8")
        name = re.search(r'versionName\s+"([^"]+)"', text)
        code = re.search(r'versionCode\s+(\d+)', text)
        return (
            name.group(1) if name else "?",
            code.group(1) if code else "?",
        )
    except Exception:
        return ("?", "?")


def checkbox(on: bool, denied: bool = False) -> str:
    if denied:
        return "[*]"
    return "[X]" if on else "[-]"


def draw(stdscr, props: dict[str, str], rows: list[str], selected: int, column: int, message: str) -> None:
    stdscr.erase()
    h, w = stdscr.getmaxyx()

    branch = current_branch()
    version_name, version_code = current_version()
    title = f" WA-KEEPER FEATURE CONTROL  |  branch: {branch}  |  v{version_name} ({version_code}) "
    stdscr.attron(curses.A_REVERSE | curses.A_BOLD)
    stdscr.addnstr(0, 0, title.ljust(max(1, w - 1)), max(1, w - 1))
    stdscr.attroff(curses.A_REVERSE | curses.A_BOLD)

    header = " LAB   LOJA   FEATURE                         DESCRIÇÃO"
    stdscr.addnstr(2, 1, header, max(1, w - 2), curses.A_BOLD)

    visible = max(1, h - 7)
    start = min(max(0, selected - visible + 1), max(0, len(rows) - visible))

    for screen_row, idx in enumerate(range(start, min(len(rows), start + visible)), start=3):
        slug = rows[idx]
        lab = bool_prop(props, f"feature.{slug}.labDefault")
        store = bool_prop(props, f"feature.{slug}.storeDefault")
        store_allowed = bool_prop(props, f"feature.{slug}.storeAllowed")
        desc = props.get(f"feature.{slug}.description", "")
        line = f" {checkbox(lab):5} {checkbox(store, denied=not store_allowed):6} {slug:<31} {desc}"

        attr = curses.A_REVERSE if idx == selected else curses.A_NORMAL
        stdscr.addnstr(screen_row, 0, line.ljust(max(1, w - 1)), max(1, w - 1), attr)

        if idx == selected:
            marker_x = 1 if column == 0 else 7
            try:
                stdscr.chgat(screen_row, marker_x, 3, attr)
            except curses.error:
                pass

    footer_y = max(4, h - 3)
    focus = "LAB" if column == 0 else "LOJA"
    help_text = f" ↑↓ navega   ←→ coluna   ESPAÇO alterna {focus}   X=ligado  -=desligado  *=negado na loja   q sai "
    stdscr.addnstr(footer_y, 0, help_text.ljust(max(1, w - 1)), max(1, w - 1), curses.A_REVERSE)

    if message:
        stdscr.addnstr(min(h - 2, footer_y + 1), 1, message, max(1, w - 2), curses.A_BOLD)

    stdscr.refresh()


def toggle(lines: list[str], props: dict[str, str], slug: str, column: int) -> tuple[list[str], str]:
    if column == 0:
        key = f"feature.{slug}.labDefault"
        new_value = not bool_prop(props, key)
        set_prop(lines, key, "true" if new_value else "false")
        save(lines)
        return lines, f"{slug}: LAB {'habilitado' if new_value else 'desabilitado'}"

    default_key = f"feature.{slug}.storeDefault"
    allowed_key = f"feature.{slug}.storeAllowed"
    new_value = not bool_prop(props, default_key)

    set_prop(lines, default_key, "true" if new_value else "false")
    if new_value:
        set_prop(lines, allowed_key, "true")
    save(lines)
    return lines, f"{slug}: LOJA {'habilitada' if new_value else 'desabilitada'}"


def main(stdscr) -> None:
    curses.curs_set(0)
    stdscr.keypad(True)
    selected = 0
    column = 0
    message = ""

    while True:
        lines = load_lines()
        props = parse(lines)
        rows = features(props)
        if not rows:
            raise SystemExit("Nenhuma feature registrada.")

        selected = max(0, min(selected, len(rows) - 1))
        draw(stdscr, props, rows, selected, column, message)
        message = ""

        key = stdscr.getch()
        if key in (ord("q"), ord("Q"), 27):
            break
        if key in (curses.KEY_UP, ord("k"), ord("K")):
            selected = (selected - 1) % len(rows)
        elif key in (curses.KEY_DOWN, ord("j"), ord("J")):
            selected = (selected + 1) % len(rows)
        elif key in (curses.KEY_LEFT, ord("h"), ord("H")):
            column = 0
        elif key in (curses.KEY_RIGHT, ord("l"), ord("L")):
            column = 1
        elif key == ord(" "):
            lines, message = toggle(lines, props, rows[selected], column)


if __name__ == "__main__":
    try:
        curses.wrapper(main)
    except KeyboardInterrupt:
        pass
