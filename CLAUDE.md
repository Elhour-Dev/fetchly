# Fetchy workflow

Work on main only, no new branches. After each finished update: bump the version, run the checks, build the installer and the APK, ask me once, then commit and push. Do not create the GitHub release: give me the file paths and the release notes (always, in a code block ready to paste; plain words for app users, since they show in the in-app update pop-up), and I publish it myself (tag vX.Y.Z, not draft or pre-release, keep the .exe and .apk endings, no SHA256 sums). Never include cookies, keys or personal files. Never force-push.

Version lives in `desktop/package.json` (`version`), `android/app/build.gradle.kts` (`versionName`, `versionCode`) and `fetchy/config.py` (`APP_VERSION`). Keep them equal to the tag.

Checks: `.venv/Scripts/python -m pytest` and `.venv/Scripts/python -m ruff check .` (backend); `android/gradlew.bat assembleDebug lintDebug` (Android).
