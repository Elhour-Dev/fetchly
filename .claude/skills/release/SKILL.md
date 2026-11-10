---
name: release
description: Prepare a new Fetchy version (bump, build, commit, push, hand files to the user). Usage /release X.Y.Z
disable-model-invocation: true
---

Release Fetchy version $ARGUMENTS (format X.Y.Z). Follow CLAUDE.md. Main branch only, never force-push.

1. Check `git status` is clean (apart from the finished update) and branch is main.
2. Bump version to X.Y.Z in:
   - `desktop/package.json` (`version`)
   - `android/app/build.gradle.kts` (`versionName` = X.Y.Z, `versionCode` + 1)
   - `fetchy/config.py` (`APP_VERSION`, shown by `/api/health`)
3. Run the `security-reviewer` agent on the pending diff. Stop if it finds cookies, keys or personal files.
4. Build:
   - Windows installer: `cd desktop && npm run dist` -> `desktop/dist/Fetchy-Setup-X.Y.Z.exe`
   - APK: `cd android && ./gradlew assembleRelease`
5. Show the user: version and files. **Ask once** before committing.
6. After a yes: commit "Release X.Y.Z: <short summary>", then `git push origin main`.
7. Do not create the GitHub release. Give the user:
   - full paths to the installer (`desktop/dist/Fetchy-Setup-X.Y.Z.exe`) and the release APK (`android/app/build/outputs/apk/release/`)
   - short release notes to paste
   - reminders: tag `vX.Y.Z`, not draft or pre-release, keep the `.exe` and `.apk` endings (the in-app Update button needs them), no SHA256 sums.
8. Never include cookies, keys or personal files.
