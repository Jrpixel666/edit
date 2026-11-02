#!/usr/bin/env bash
# Paste this into the Claude Code cloud environment's "Setup script" field
# (environment menu in the session title bar → Edit) to provision the
# Android SDK/NDK/CMake once per environment, so future sessions can build
# this project immediately instead of installing ~1GB of tooling by hand.
set -e
ANDROID_HOME=/opt/android-sdk
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -sS -L -o /tmp/cmdline-tools.zip \
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  unzip -q /tmp/cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm /tmp/cmdline-tools.zip
fi
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses >/dev/null
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;27.2.12479018" "cmake;3.22.1"
echo "sdk.dir=$ANDROID_HOME" > "$(dirname "$0")/../local.properties"

# Maven Central (repo.maven.apache.org) 429-rate-limits shared sandbox IPs; mirror
# through Google's read-only GCS mirror of it for this environment's Gradle builds.
mkdir -p ~/.gradle/init.d
cat > ~/.gradle/init.d/maven-central-mirror.gradle.kts <<'EOF'
allprojects {
    repositories.all {
        if (this is org.gradle.api.artifacts.repositories.MavenArtifactRepository && url.toString().startsWith("https://repo.maven.apache.org/maven2")) {
            url = uri("https://maven-central.storage-download.googleapis.com/maven2")
        }
    }
}
settingsEvaluated {
    pluginManagement.repositories.all {
        if (this is org.gradle.api.artifacts.repositories.MavenArtifactRepository && url.toString().startsWith("https://repo.maven.apache.org/maven2")) {
            url = uri("https://maven-central.storage-download.googleapis.com/maven2")
        }
    }
    dependencyResolutionManagement.repositories.all {
        if (this is org.gradle.api.artifacts.repositories.MavenArtifactRepository && url.toString().startsWith("https://repo.maven.apache.org/maven2")) {
            url = uri("https://maven-central.storage-download.googleapis.com/maven2")
        }
    }
}
EOF

echo "Android SDK ready at $ANDROID_HOME (platform 35, build-tools 35.0.0, NDK 27.2.12479018, cmake 3.22.1)."
echo "Run ./scripts/fetch-deps.sh once per checkout, then ./gradlew assembleDebug."
