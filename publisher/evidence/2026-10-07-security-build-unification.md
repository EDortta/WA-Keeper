# Security and build unification — 2026-10-07

Implemented:
- signing passwords removed from tracked gradle.properties;
- release build no longer uses signingConfigs.debug;
- release signing now comes from environment variables or ignored release.properties;
- release.properties, keystores, databases and raw diagnostic captures are ignored;
- private database/shared-preferences payloads removed from current development/play trees;
- raw wa-keeper-diagnose-* directories removed from current development/play trees;
- scripts/build.sh is the single Android compiler entrypoint;
- android deploy, ASR helpers and CI delegate compilation to scripts/build.sh;
- obsolete scripts/build-devel3.sh and publisher/scripts/build-play.sh removed;
- security-check.sh added to detect regressions;
- setup-signing.sh added to create a new local release key outside the repository.

Important remaining security action:
The old signing secrets and diagnostic payloads still exist in Git history. Treat the old credential set as compromised. History rewrite/purge and credential rotation are separate destructive operations and must be completed before public release.

Status: IMPLEMENTED at repository tree level. Local build validation still required on devel3.
