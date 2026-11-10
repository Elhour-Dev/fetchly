---
name: check-versions
description: Check that desktop/package.json, build.gradle.kts and the latest git tag all have the same version.
disable-model-invocation: true
---

Read only these three values and compare them:
- `version` in `desktop/package.json`
- `versionName` (and `versionCode`) in `android/app/build.gradle.kts`
- latest tag: `git describe --tags --abbrev=0`

Reply in one short table. Say clearly if any differ, and which one to fix. Change nothing.
