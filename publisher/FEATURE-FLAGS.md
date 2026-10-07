# Build profiles and feature flags

There is one compiler entrypoint:

    bash scripts/build.sh

## Profiles

LAB is the default profile used for development and testing.

STORE is the Google Play candidate profile.

Any branch may generate LAB.

Only the `play` branch should be treated as the source of an official STORE candidate.

Examples:

    bash scripts/build.sh --lab
    bash scripts/build.sh --lab --release
    bash scripts/build.sh --store

`WA_STORE_BUILD` is a compile-time boolean.

Each registered feature also becomes `BuildConfig.WA_FEATURE_*`.

A STORE build may only enable a feature with `feature.<name>.storeAllowed=true`.

Contradictions are build errors.

## Interactive selector

Run:

    python3 scripts/features.py

The selector shows:

    [X] LAB   [ ] LOJA   feature_slug   short description

Controls:

- Up/Down: select feature
- Left/Right: select LAB or LOJA
- Space: toggle selected profile
- q or Esc: exit

Changes are written immediately to `publisher/features.properties`.

LAB toggles `labDefault`.

LOJA toggles `storeDefault`.

Turning LOJA on also sets `storeAllowed=true`, because a STORE build cannot enable a feature that is not store-approved.

Turning LOJA off removes the feature from the default STORE build but keeps `storeAllowed` unchanged.

## Version awareness

The selector must show the current application version and its lifecycle status.

Planned display:

    WA Keeper 1.0.10   versionCode 10   DEVELOPMENT / WORKING

or:

    WA Keeper 1.0.10   versionCode 10   PLAY / SUBMITTED

Only the current WORKING version may be edited.

Previous versions are read-only.

When a version becomes CANDIDATE, its feature configuration must be snapshotted under:

    publisher/versions/<versionName>.properties

The snapshot records the LAB and LOJA state of every feature, plus version metadata and commit identity.

## Version lifecycle

- WORKING
- CANDIDATE
- SUBMITTED
- APPROVED
- PUBLISHED

Once a version is SUBMITTED, its feature configuration is frozen.

If the candidate changes after submission, it becomes a new candidate and normally requires a new `versionCode`.

## Source of truth

Current editable configuration:

    publisher/features.properties

Historical immutable configuration:

    publisher/versions/<versionName>.properties

New features start with `storeAllowed=false` until explicitly reviewed and approved.

The shell validates first and Gradle validates again.

Existing features must be wired to their respective `BuildConfig.WA_FEATURE_*` guards. Hiding UI alone is not sufficient; code, manifest entries, permissions, services and dependencies must be isolated when necessary.
