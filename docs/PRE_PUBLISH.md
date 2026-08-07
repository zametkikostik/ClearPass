# Pre-publish checklist

1. Local `./gradlew assembleDebug`
2. Install APK on arm64 device
3. Test START with own VLESS or curated sub
4. Deliver `libsingbox.so` via LFS or Release + SINGBOX_URL
5. Actions → Build APK → Run workflow

Do not publish until local APK works.
