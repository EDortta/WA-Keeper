#!/usr/bin/env python3
"""Read-only Android signing compatibility diagnosis. Never installs or exposes secrets."""
import argparse
import datetime as dt
import json
import tempfile
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

APP_ID = "br.com.wanotifkeeper"

def call(argv):
    return subprocess.run(argv, text=True, capture_output=True, check=False)

def cert(apksigner, apk):
    result = call([apksigner, "verify", "--print-certs", str(apk)])
    digests = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", result.stdout)
    return (result.returncode, [d.replace(":", "").lower() for d in digests])

def recorded_main():
    """Write safe local evidence for every execution, including failure."""
    state = Path(os.environ.get("XDG_STATE_HOME", str(Path.home() / ".local/state"))) / "wa-keeper" / "signing"
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    report = state / f"diagnostic-{stamp}.json"
    import contextlib
    import io
    buffer = io.StringIO()
    code = 2
    try:
        with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
            code = main()
    except SystemExit as exc:
        code = exc.code if isinstance(exc.code, int) else 2
    except Exception as exc:
        buffer.write(f"ERROR: exception_type={type(exc).__name__}\n")
    output = buffer.getvalue()
    allow = ("package=", "installed_certificate_sha256=", "candidate_certificate_sha256=",
             "signature_match=", "candidate_certificate=", "read_only=", "NOT_INSTALLED:")
    findings = [line.strip() for line in output.splitlines() if line.startswith(allow)]
    reason = "none" if code == 0 else "verification_failed"
    if "cannot verify installed certificate" in output:
        reason = "installed_certificate_parse_or_verification"
    elif "could not retrieve installed base APK" in output:
        reason = "installed_apk_pull"
    elif "apksigner not found" in output:
        reason = "apksigner_unavailable"
    elif "exactly one authorized device" in output:
        reason = "device_selection"
    elif "NOT_INSTALLED:" in output:
        reason = "package_not_installed"
    evidence = {"error_category": reason, "schema": 1, "timestamp_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
                "tool": "signing-diagnose", "status": "success" if code == 0 else "failed",
                "exit_code": code, "findings": findings}
    with tempfile.NamedTemporaryFile(dir=state, mode="w", prefix=".diag-", delete=False) as tmp:
        json.dump(evidence, tmp, indent=2)
        tmp.write("\n")
        tmp.flush()
        os.fsync(tmp.fileno())
        temporary = Path(tmp.name)
    temporary.chmod(0o600)
    os.replace(temporary, report)
    print(output, end="")
    print(f"Evidence saved locally: {report}")
    return code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, help="APK candidate; optional for installed-only diagnosis")
    parser.add_argument("--serial", help="ADB device serial")
    args = parser.parse_args()
    adb = shutil.which("adb")
    if not adb:
        parser.error("adb not available")
    base = [adb] + (["-s", args.serial] if args.serial else [])
    devices = call([adb, "devices"]).stdout.splitlines()
    connected = [line.split()[0] for line in devices[1:] if len(line.split()) > 1 and line.split()[1] == "device"]
    if not args.serial and len(connected) != 1:
        parser.error("specify --serial; exactly one authorized device is required")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    signers = []
    if sdk:
        signers = sorted((Path(sdk) / "build-tools").glob("*/apksigner"))
    signer = shutil.which("apksigner") or (str(signers[-1]) if signers else None)
    if not signer:
        parser.error("apksigner not found in PATH or Android SDK")
    paths = call(base + ["shell", "pm", "path", APP_ID])
    remote = next((x[len("package:"):].strip() for x in paths.stdout.splitlines() if x.startswith("package:")), None)
    if not remote:
        print(f"NOT_INSTALLED: {APP_ID}")
        return 2
    # Pull APK to a private tempdir; never read application databases or personal data.
    with tempfile.TemporaryDirectory(prefix="wa-signing-") as temp:
        installed = Path(temp) / "installed.apk"
        pulled = call(base + ["pull", remote, str(installed)])
        if pulled.returncode:
            print("ERROR: could not retrieve installed base APK", file=sys.stderr)
            return 2
        code, existing = cert(signer, installed)
        if code or not existing:
            print("ERROR: cannot verify installed certificate", file=sys.stderr)
            return 2
        print(f"package={APP_ID}")
        print(f"installed_certificate_sha256={','.join(existing)}")
        if args.apk:
            if not args.apk.is_file():
                parser.error("candidate APK not found")
            code, candidate = cert(signer, args.apk)
            if code or not candidate:
                print("candidate_certificate=UNVERIFIED")
                return 2
            print(f"candidate_certificate_sha256={','.join(candidate)}")
            match = existing == candidate
            print("signature_match=" + ("YES" if match else "NO"))
            if not match:
                return 3
        else:
            print("candidate_certificate=NOT_PROVIDED")
    print("read_only=YES; no install, uninstall, app-data access or keystore export")
    return 0

if __name__ == "__main__":
    sys.exit(recorded_main())
