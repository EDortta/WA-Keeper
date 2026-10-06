#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -d "evidence" ]; then
    EVIDENCE_DIR="evidence"
else
    EVIDENCE_DIR="../evidence"
fi

EVIDENCE_FILENAME="$EVIDENCE_DIR/$(date "+%Y-%m-%dT%H:%M").txt"

"$SCRIPT_DIR/check-play-readiness.sh" > $EVIDENCE_FILENAME

git add $EVIDENCE_FILENAME
git commit -am "Evidencia $EVIDENCE_FILENAME"
git pull && git push || echo "Error while collecting evidence"