# TDLib setup

This project depends on **TDLight-Java**, which ships prebuilt native
libraries for `arm64-v8a`, `armeabi-v7a`, and `x86_64` via Maven Central.
In normal operation you do **not** need to compile TDLib yourself — Gradle
downloads the `.so` files automatically.

This document covers two cases where you might need to do more:

1. The Maven artifact is unreachable from your network
2. You want to build TDLib from source (e.g., to use a newer TDLib version
   than TDLight has packaged)

---

## Option 1 — Use TDLight Maven artifacts (default)

Already wired in `core/telegram/build.gradle.kts`:

```kotlin
api(libs.tdlight.java)        // it.tdlight:tdlight-java:3.4.0+fork.1
implementation(libs.tdlight.natives)  // it.tdlight:tdlight-natives:3.4.0+fork.1
```

No further action needed. If you see `UnsatisfiedLinkError`, check that your
device ABI matches one of the three supported ABIs.

---

## Option 2 — Build TDLib from source

Useful if TDLight hasn't packaged the TDLib version you need, or if you want
to patch TDLib itself.

### Prerequisites

- CMake 3.16+
- OpenSSL 1.1.1 dev headers
- gperf
- zlib dev headers
- JDK 17
- Android NDK r25+

### Build steps

```bash
# 1. Clone TDLib
git clone https://github.com/tdlib/td.git
cd td

# 2. Pick a release tag
git checkout v1.8.20

# 3. Set up NDK path
export ANDROID_NDK_ROOT=/path/to/Android/Sdk/ndk/25.2.9519653

# 4. Build for each ABI (this takes ~20 minutes per ABI on a modern laptop)
for ABI in arm64-v8a armeabi-v7a x86_64; do
  mkdir -p build-$ABI && cd build-$ABI
  cmake -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake \
        -DANDROID_ABI=$ABI \
        -DANDROID_PLATFORM=android-26 \
        ..
  cmake --build . -- -j$(nproc)
  cd ..
done

# 5. Copy the resulting .so files into your project
mkdir -p /home/z/my-project/download/TelegramDrive-Android/core/telegram/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}
cp build-arm64-v8a/libtdjni.so       core/telegram/src/main/jniLibs/arm64-v8a/
cp build-armeabi-v7a/libtdjni.so    core/telegram/src/main/jniLibs/armeabi-v7a/
cp build-x86_64/libtdjni.so         core/telegram/src/main/jniLibs/x86_64/
```

### Switching the build to use local .so

Edit `core/telegram/build.gradle.kts`:

```kotlin
// Replace:
api(libs.tdlight.java)
implementation(libs.tdlight.natives)

// With:
api(libs.tdlight.java)            // still need the Java bindings
// Natives now come from jniLibs/ — no Maven dep needed
```

The `jniLibs/` directory is automatically picked up by AGP and bundled into
the APK. Set `jniLibs.useLegacyPackaging = true` (already done in
`app/build.gradle.kts`) so the libs are extracted at install time, which
TDLib requires.

---

## Verifying your setup

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s TGDrive:V TDLib:V
```

On launch you should see:

```
I/TGDrive: TDLib client initialised
```

If you see `UnsatisfiedLinkError`, the `.so` for your device's ABI is missing.
