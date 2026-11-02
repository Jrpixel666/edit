package dev.apkforge.core
import android.content.Context
import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import java.io.File
import java.util.zip.*

/** One imported APK = one project folder:  original.apk, src/ (decoded), build/ (outputs). */
class Workspace(private val ctx: Context, val shell: Shell) {
  val root = File(ctx.filesDir, "projects").apply { mkdirs() }
  fun projects() = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: emptyList()

  /**
   * Accepts .apk, or split bundles (.apks / .xapk / .apkm / zip of APKs).
   * Split layout: original.apk = base (decoded + editable), splits/ *.apk = config splits (kept, re-signed on build).
   */
  fun import(name: String, input: java.io.InputStream, log: (String) -> Unit): File {
    val dir = File(root, name.substringBeforeLast('.')).apply { deleteRecursively(); mkdirs() }
    val tmp = File(dir, "import.bin"); input.use { i -> tmp.outputStream().use { i.copyTo(it) } }
    val inner = ZipFile(tmp).use { z -> z.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".apk") }.map { it.name }.toList() }
    val isApk = ZipFile(tmp).use { it.getEntry("AndroidManifest.xml") != null }
    if (isApk || inner.isEmpty()) tmp.renameTo(File(dir, "original.apk"))
    else {
      log("Split bundle: ${inner.size} APKs")
      val splits = File(dir, "splits").apply { mkdirs() }
      ZipFile(tmp).use { z -> inner.forEach { n ->
        val out = File(splits, n.substringAfterLast('/')); z.getInputStream(z.getEntry(n)).use { i -> out.outputStream().use { i.copyTo(it) } } } }
      // base = the split with no "split" attribute: by name convention, else the biggest one containing classes.dex
      val base = splits.listFiles()!!.firstOrNull { it.name == "base.apk" }
        ?: splits.listFiles()!!.filter { f -> ZipFile(f).use { it.getEntry("classes.dex") != null } }.maxByOrNull { it.length() }
        ?: error("no base APK found in bundle")
      base.renameTo(File(dir, "original.apk")); tmp.delete()
      log("base = ${base.name}; splits: ${splits.list()?.joinToString()}")
    }
    decode(dir, log); return dir
  }

  fun splits(dir: File) = File(dir, "splits").listFiles { f -> f.extension == "apk" }?.sortedBy { it.name } ?: emptyList()

  /** Optional: merge base + splits into ONE standalone APK (uses APKEditor jar inside proot). */
  fun merge(dir: File, log: (String) -> Unit): File {
    check(shell.ready) { "install toolchain first" }
    shell.run("""[ -f /opt/APKEditor.jar ] || curl -L -o /opt/APKEditor.jar \
      ${'$'}(curl -s https://api.github.com/repos/REAndroid/APKEditor/releases/latest | grep -o 'https://[^"]*\.jar' | head -1)
      rm -rf m && mkdir m && cp original.apk m/base.apk && cp splits/*.apk m/ && java -jar /opt/APKEditor.jar m -i m -o merged.apk -f
      rm -rf m splits original.apk && mv merged.apk original.apk""", dir, log)
    decode(dir, log); return dir
  }

  /** Full decode with apktool (resources + manifest as text) if toolchain present, else pure-JVM smali-only mode. */
  fun decode(dir: File, log: (String) -> Unit) {
    val src = File(dir, "src").apply { deleteRecursively() }
    if (shell.ready) {
      log("apktool d …"); shell.run("apktool d -f -o src original.apk", dir, log)
    } else {
      log("Toolchain not installed → smali-only mode (resources stay binary)")
      ZipFile(File(dir, "original.apk")).use { z -> z.entries().asSequence().forEach { e ->
        val out = File(src, "raw/" + e.name); if (e.isDirectory) return@forEach
        out.parentFile!!.mkdirs(); z.getInputStream(e).use { i -> out.outputStream().use { i.copyTo(it) } } } }
      File(src, "raw").listFiles { f -> f.name.matches(Regex("classes\\d*\\.dex")) }?.forEach { dex ->
        val outDir = File(src, "smali" + dex.name.removePrefix("classes").removeSuffix(".dex").let { if (it.isEmpty()) "" else "_classes$it" })
        log("baksmali ${dex.name}")
        Baksmali.disassembleDexFile(DexFileFactory.loadDexFile(dex, Opcodes.getDefault()), outDir, Runtime.getRuntime().availableProcessors(), BaksmaliOptions())
        dex.delete() }
    }
  }

  /** Rebuild → align → sign. Returns signed APK. */
  fun build(dir: File, log: (String) -> Unit): File {
    val b = File(dir, "build").apply { mkdirs() }
    val unsigned = File(b, "unsigned.apk"); val signed = File(b, "${dir.name}-mod.apk")
    if (File(dir, "src/apktool.yml").exists()) {
      val rc = shell.run("apktool b -f --use-aapt2 -o build/unsigned.apk src && zipalign -f -p 4 build/unsigned.apk build/aligned.apk", dir, log)
      check(rc == 0) { "apktool build failed (exit $rc) — see log" }
      File(b, "aligned.apk").renameTo(unsigned)
    } else {
      val raw = File(dir, "src/raw")
      File(dir, "src").listFiles { f -> f.name.startsWith("smali") }?.forEach { sd ->
        val name = "classes" + sd.name.removePrefix("smali").removePrefix("_classes") + ".dex"
        log("smali → $name")
        check(Smali.assemble(SmaliOptions().apply { outputDexFile = File(raw, name).path; jobs = 4 }, sd.path)) { "smali errors in ${sd.name}" }
      }
      zipAligned(raw, unsigned)
    }
    Signer.sign(ctx, unsigned, signed); log("✔ signed: ${signed.name}")
    // every split must carry the same signature as the base or install fails
    val outSplits = File(b, "splits").apply { deleteRecursively(); mkdirs() }
    splits(dir).forEach { s -> Signer.sign(ctx, stripSig(s, File(ctx.cacheDir, s.name)), File(outSplits, s.name)); log("✔ re-signed split ${s.name}") }
    return signed
  }

  /** Copy APK minus old META-INF signature files (apksig re-aligns on sign). */
  private fun stripSig(src: File, out: File): File {
    ZipFile(src).use { z -> ZipOutputStream(out.outputStream().buffered()).use { o ->
      z.entries().asSequence().filter { !it.name.startsWith("META-INF/") }.forEach { e ->
        val ne = ZipEntry(e.name).apply { method = e.method; if (e.method == ZipEntry.STORED) { size = e.size; crc = e.crc } }
        o.putNextEntry(ne); z.getInputStream(e).use { it.copyTo(o) }; o.closeEntry() } } }
    return out
  }

  /** Zip with 4-byte alignment for STORED entries and resources.arsc/.so uncompressed (Android R+ requirement). */
  private fun zipAligned(src: File, out: File) {
    ZipOutputStream(out.outputStream().buffered()).use { z ->
      src.walkTopDown().filter { it.isFile && !it.path.contains("META-INF") }.forEach { f ->
        val n = f.relativeTo(src).path; val bytes = f.readBytes()
        val store = n == "resources.arsc" || n.endsWith(".so") || n.endsWith(".png")
        val e = ZipEntry(n)
        if (store) { e.method = ZipEntry.STORED; e.size = bytes.size.toLong(); e.crc = CRC32().apply { update(bytes) }.value }
        z.putNextEntry(e); z.write(bytes); z.closeEntry() }
    }
  }
}
