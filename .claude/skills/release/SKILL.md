---
name: release
description: Publish a new Fetchy version (bump, commit, push, build, checksums, GitHub release). Usage /release X.Y.Z
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
5. Make `SHA256SUMS.txt` for the installer and the APK (`sha256sum` or `Get-FileHash`).
6. Show the user: version, files, checksums. **Ask once** before publishing.
7. After a yes: commit "Release X.Y.Z: <short summary>", `git push origin main`, then
   `gh release create vX.Y.Z <installer> <apk> SHA256SUMS.txt --title "vX.Y.Z" --notes "<notes>"`
   (use the user's proxy and gh login).
8. Never include cookies, keys or personal files. Report the release URL.
