package dev.apkforge.core
import android.content.Context
import java.io.File

/** Runs commands inside a bundled proot + Debian arm64 rootfs. No Termux needed. */
class Shell(private val ctx: Context) {
  val rootfs = File(ctx.filesDir, "rootfs")
  val ready get() = File(rootfs, "usr/bin/apt").exists()
  private val proot get() = File(ctx.applicationInfo.nativeLibraryDir, "libproot.so")
  private val loader get() = File(ctx.applicationInfo.nativeLibraryDir, "libproot-loader.so")

  /**
   * proot's DT_NEEDED entry is "libtalloc.so.2", but Android only packages native libs whose
   * filename ends in exactly ".so" (jniLibs/libtalloc.so ships the same binary under a valid name).
   * Materialize the versioned name once in a writable dir so the dynamic linker can find it via
   * LD_LIBRARY_PATH (a spawned proot process doesn't inherit the app's own linker namespace).
   */
  private val libDir by lazy {
    File(ctx.filesDir, "lib").apply {
      mkdirs()
      val versioned = File(this, "libtalloc.so.2")
      if (!versioned.exists()) File(ctx.applicationInfo.nativeLibraryDir, "libtalloc.so").copyTo(versioned)
    }
  }
  private fun ProcessBuilder.withProotEnv() = apply {
    environment()["PROOT_LOADER"] = loader.path
    environment()["LD_LIBRARY_PATH"] = "${libDir.path}:${ctx.applicationInfo.nativeLibraryDir}"
  }

  fun run(cmd: String, cwd: File? = null, onLine: (String) -> Unit = {}): Int {
    val bind = mutableListOf("-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${ctx.filesDir}/projects:/projects")
    val pb = ProcessBuilder(listOf(proot.path, "--link2symlink", "-0", "-r", rootfs.path) + bind +
        listOf("-w", cwd?.let { "/projects/" + it.relativeTo(File(ctx.filesDir, "projects")).path } ?: "/root",
          "/usr/bin/env", "-i", "HOME=/root", "PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
          "LANG=C.UTF-8", "/bin/bash", "-lc", cmd)).withProotEnv()
    pb.environment()["PROOT_TMP_DIR"] = ctx.cacheDir.path
    pb.redirectErrorStream(true)
    val p = pb.start()
    p.inputStream.bufferedReader().forEachLine(onLine)
    return p.waitFor()
  }

  /** One-time: unpack Debian rootfs (downloaded or sideloaded tarball) and install the Android toolchain. */
  fun bootstrap(tarball: File, log: (String) -> Unit) {
    rootfs.mkdirs(); File(ctx.filesDir, "projects").mkdirs()
    val pb = ProcessBuilder(proot.path, "--link2symlink", "-0", "/system/bin/tar", "-xJf", tarball.path, "-C", rootfs.path)
      .withProotEnv()
    pb.redirectErrorStream(true)
    pb.start().apply { inputStream.bufferedReader().forEachLine(log); waitFor() }
    File(rootfs, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
    run("""apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      openjdk-17-jdk-headless apktool aapt aapt2 zipalign apksigner dexdump smali jadx \
      android-sdk-platform-23 git zip unzip python3 nano ripgrep && apt-get clean""", onLine = log)
  }
}
