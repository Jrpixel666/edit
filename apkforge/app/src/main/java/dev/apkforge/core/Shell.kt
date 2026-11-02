package dev.apkforge.core
import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths

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
    log("extracting rootfs…")
    extractTarXz(tarball, rootfs)
    File(rootfs, "etc").mkdirs()
    File(rootfs, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
    run("""apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      openjdk-17-jdk-headless apktool aapt aapt2 zipalign apksigner dexdump smali jadx \
      android-sdk-platform-23 git zip unzip python3 nano ripgrep && apt-get clean""", onLine = log)
  }

  /**
   * Android's /system/bin/tar has no xz support (no bundled "xz" binary to shell out to), so
   * "tar -xJf" silently fails on every device ("tar: exec xz: No such file or directory").
   * Extract in pure Kotlin instead — no proot or system tar involved.
   */
  private fun extractTarXz(tarball: File, dest: File) {
    val execMask = 0b001_001_001 // any of owner/group/other execute bits
    XZCompressorInputStream(BufferedInputStream(tarball.inputStream())).use { xz ->
      TarArchiveInputStream(xz).use { tar ->
        generateSequence { tar.nextEntry as? org.apache.commons.compress.archivers.tar.TarArchiveEntry }.forEach { entry ->
          // proot-distro's rootfs tarballs wrap everything in one top-level directory
          // (e.g. "debian-bookworm-aarch64/etc/..."), so strip it; the entry for that directory
          // itself strips to "" and is skipped (dest already exists as rootfs).
          val relPath = entry.name.substringAfter('/', "")
          if (relPath.isEmpty()) return@forEach
          val out = File(dest, relPath)
          runCatching {
            when {
              entry.isDirectory -> out.mkdirs()
              entry.isSymbolicLink -> {
                out.parentFile?.mkdirs()
                out.toPath().let { p -> if (!Files.isSymbolicLink(p)) Files.createSymbolicLink(p, Paths.get(entry.linkName)) }
              }
              else -> { // regular files; device nodes/fifos aren't present in proot-distro's rootfs images
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { o -> tar.copyTo(o) }
                if (entry.mode and execMask != 0) out.setExecutable(true, false)
              }
            }
          }
        }
      }
    }
  }
}
