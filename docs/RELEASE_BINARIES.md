# Delivery of libsingbox.so to GitHub

## Option A — Git LFS

```bash
cd ClearPass
git lfs install
git lfs track "*.so"
# remove jniLibs/**/*.so from .gitignore
git add .gitattributes
git add app/src/main/jniLibs/arm64-v8a/libsingbox.so
git add app/src/main/jniLibs/arm64-v8a/libhev-jni.so
git commit -m "Add native cores via LFS"
git push
```

## Option B — Release assets + Actions variables

1. Create Release v0.2.0
2. Upload libsingbox.so and libhev-jni.so
3. Repo Settings → Variables:
   - SINGBOX_URL = release asset URL
   - HEV_JNI_URL = release asset URL

See `.github/workflows/build.yml`
