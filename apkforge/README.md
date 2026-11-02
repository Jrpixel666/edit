# ApkForge — offline APK editor + local AI agent for Android

Share/open any `.apk` → it decodes → edit by hand or tell the chat what to change → **Rebuild & install**. All on-phone, no Termux.

## Build once (PC)
    ./scripts/fetch-deps.sh          # llama.cpp + arm64 proot
    ./gradlew assembleDebug           # Android Studio Ladybug+, NDK 27
    adb install app/build/outputs/apk/debug/app-debug.apk

## First run (phone)
1. **Terminal → Install toolchain** (one-time ~600 MB; or "From file" with a rootfs tarball for fully offline setup).
   Installs inside proot Debian: JDK 17, apktool, aapt/aapt2, zipalign, apksigner, smali, jadx, git, python3.
   Without it, ApkForge still works in *smali-only* mode (code edits, no resource/manifest edits).
2. **Models → search** e.g. `qwen2.5-coder 7b gguf` → tap a file → tap it again to load.
   8 GB RAM phones: 3B–7B at Q4_K_M. 12 GB+: 7B–14B.
3. **Projects → Import APK** (or Share → ApkForge from any file manager).

## Workflow
| Tab | What |
|---|---|
| Projects | import, pick active, **Rebuild & install** (apktool b → zipalign → v1/v2/v3 sign) |
| Files | browse decoded tree, edit smali/xml in a mono editor |
| Chat | agent with tools: list, search, read, replace, write, shell, build — it edits and rebuilds itself |
| Models | Hugging Face GGUF search, resumable download, load |
| Terminal | full Debian shell; project mounted at `/projects/<name>` |

## Notes / limits
- **Signing:** rebuilt APKs use an ApkForge key, so to *update* an already-installed copy of your app you must uninstall first,
  or drop your own keystore into the Terminal and run `apksigner sign --ks my.jks build/*.apk`.
- `targetSdk 28` is deliberate: Android 10+ blocks exec from app data for newer targets (same trick Termux uses). Fine for sideloading, not for Play Store.
- **Split APKs** (`.apks` / `.xapk` / `.apkm`): the base is decoded for editing; config splits (abi, density, language) are kept
  and re-signed with the same key on every build, then installed together in one PackageInstaller session.
  Projects → **Merge to single APK** folds everything into one standalone APK instead (uses APKEditor in the toolchain).
- Small models make mistakes in smali; the agent rebuilds after edits so errors get fed back to it.
