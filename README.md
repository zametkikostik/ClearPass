# ClearPass **0.3.0**

Android DPI-bypass client (VLESS-Reality / Hysteria2 / TUIC) via **sing-box**.

## What's new in 0.3.0

- **Autostart & reconnect** — optional auto-connect after phone boot (off by default, toggle in settings); Wi-Fi ↔ LTE switches health-check the server and rotate configs automatically via the existing `CORE_DIED` mechanism
- **Real-delay config testing** — top configs are verified with a full HTTP round-trip (`cp.cloudflare.com/generate_204`) through a temporary local sing-box SOCKS; servers that hold TCP but carry no traffic are dropped, verified ones rank higher
- **In-app self-update** — checks GitHub Releases, downloads `ClearPass-release.apk` and hands it to the system installer (user confirmation required, cache-only, no storage permissions)
- **Split tunneling** — route all traffic, only selected apps, or everything except selected apps (applied on next connect)
- **Smarter list worker** — ETag/304 support so unchanged lists are not re-downloaded; 12 h cache freshness TTL (stale configs rank lower, >24 h are pruned); emergency re-fetch when rotation runs out of live servers

## Sources

Curated subscriptions only (not full-internet crawl):
- [igareck/vpn-configs-for-russia](https://github.com/igareck/vpn-configs-for-russia) white/black lists + mirrors
- Backup: Stintik-123, FreeProxyList mirrors

## Build

```bash
./gradlew assembleDebug
```

Or GitHub Actions → **Build APK** → Run workflow.

Native cores (`libsingbox.so`, `libhev-jni.so`) via Git LFS or release URLs — see `docs/RELEASE_BINARIES.md`.

## Architecture

System VpnService TUN + sing-box SOCKS (auth) + hev TUN→SOCKS (JNI preferred).
