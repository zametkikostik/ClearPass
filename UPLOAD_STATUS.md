# Upload status

Partial publish via API (2026-08-06).

## Already on GitHub

- README, .gitignore, Gradle root files
- `.github/workflows/build.yml` (workflow_dispatch + LFS/geo/hev)
- `app/build.gradle.kts`, AndroidManifest, basic res
- Some Kotlin stubs (ConnectionState, HevJni)
- docs/ARCHITECTURE, RELEASE_BINARIES

## NOT fully uploaded yet

- Most of `app/src/main/java/**` (MainActivity, VpnService, ConnectionManager, SafeSources, …)
- Real `gradlew` / `gradle-wrapper.jar`
- `libsingbox.so` / `libhev-jni.so` (use LFS or Release)
- geoip.db / geosite.db (CI downloads them)

## Finish from your machine (recommended)

```bash
# 1) Clone empty/partial repo
git clone https://github.com/zametkikostik/ClearPass.git
cd ClearPass

# 2) Replace with full local project tree (from the agent workspace or your copy)
#    Copy all sources, keep jniLibs binaries

# 3) LFS for .so
git lfs install
git lfs track "*.so"
# edit .gitignore — remove jniLibs/**/*.so if committing cores

git add -A
git commit -m "Complete ClearPass sources + optional cores"
git push -u origin main

# 4) Actions → Build APK → Run workflow
```

Or upload `libsingbox.so` as Release asset and set variable `SINGBOX_URL`.
