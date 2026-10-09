#!/usr/bin/env python3
"""Read-only Android signing compatibility diagnosis. Never installs or exposes secrets."""
import argparse
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
    digests = re.findall(r"Signer #\\d+ certificate SHA-256 digest:\\s*([0-9a-fA-F:]+)", result.stdout)
    return (result.returncode, [d.replace(":", "").lower() for d in digests])

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
    import tempfile
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
            print("signature_match=" + ("YES" if existing == candidate else "NO"))
        else:
            print("candidate_certificate=NOT_PROVIDED")
    print("read_only=YES; no install, uninstall, app-data access or keystore export")
    return 0

if __name__ == "__main__":
    sys.exit(main())
