# Build profiles and feature flags

There is one compiler entrypoint: `bash scripts/build.sh`.

Profiles: `lab` for experimental builds and `store` for Google Play candidate/official builds.

`WA_STORE_BUILD` is a compile-time boolean. Each registered feature also becomes `BuildConfig.WA_FEATURE_*`.

A store build may only enable a feature with `feature.<name>.storeAllowed=true`. Contradictions are build errors, never silent exclusions.

Examples:

    bash scripts/build.sh --lab
    bash scripts/build.sh --store --bundle
    bash scripts/build.sh --lab --feature media_share_accessibility
    bash scripts/build.sh --store --only search,entities,tts,local_transcription

Source of truth: `publisher/features.properties`.

New features start with `storeAllowed=false` until explicitly reviewed and approved.

The shell validates first and Gradle validates again. Direct Gradle invocation cannot bypass store policy.

Existing features must be wired to their respective `BuildConfig.WA_FEATURE_*` guards. A feature is not truly excluded until code, UI, manifest entries and dependencies are gated or isolated.


## Interactive selector

Run:

    bash scripts/features.sh

The terminal selector shows four columns:

    [X] LAB   [ ] LOJA   feature_slug   short description

Controls:

- Up/Down: select feature
- Left/Right: select LAB or LOJA
- Space: toggle selected profile
- q or Esc: exit

Changes are written immediately to `publisher/features.properties`.

LAB toggles `labDefault`.

LOJA toggles `storeDefault`. Turning LOJA on also sets `storeAllowed=true`, because a store build is forbidden from enabling a feature that is not store-approved. Turning LOJA off removes the feature from the default store build but keeps `storeAllowed` unchanged.
