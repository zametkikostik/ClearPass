# hev JNI (libhev-jni.so)

Build with NDK from heiher/hev-socks5-tunnel:

```
APP_CFLAGS=-DPKGNAME=com/clearpass/app/tunnel -DCLSNAME=HevJni
```

Copy result to `app/src/main/jniLibs/arm64-v8a/libhev-jni.so`.

Fallback: CLI `libhev-socks5-tunnel.so` (limited on Android without FD).
