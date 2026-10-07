# Release signing security

Release signing secrets must never be committed.

Supported inputs, in precedence order:
1. environment variables `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`;
2. local root file `release.properties`, ignored by Git.

`gradle.properties` must contain no signing secret.

Because signing credentials were previously committed, the exposed credential set must be treated as compromised and replaced before any Play release.

Historical Git cleanup is a separate destructive operation and must be performed deliberately after current branches are clean.
