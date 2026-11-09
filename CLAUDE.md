# Fetchy workflow

Work on main only, no new branches. After each finished update: bump the version, commit, push, create a GitHub release (tag vX.Y.Z) with the installer, the APK and SHA256 checksums, via my proxy and gh login. Ask me once before publishing. Never include cookies, keys or personal files. Never force-push.

Where the version lives: `desktop/package.json` (`version`) and `android/app/build.gradle.kts` (`versionName`, `versionCode`). Keep them equal to the tag.
