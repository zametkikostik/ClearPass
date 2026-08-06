# ClearPass **0.2.0**

Android DPI-bypass client (VLESS-Reality / Hysteria2 / TUIC) via **sing-box**.

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
