package dev.apkforge.llm
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File

data class HfModel(val id: String, val downloads: Int, val likes: Int)
data class HfFile(val name: String, val size: Long)

/** In-app Hugging Face search, restricted to GGUF so everything runs on-device afterwards. */
object HuggingFace {
  private val http = OkHttpClient()
  private fun get(url: String) = http.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }

  fun search(q: String): List<HfModel> = JSONArray(get(
    "https://huggingface.co/api/models?search=${java.net.URLEncoder.encode(q, "UTF-8")}&filter=gguf&sort=downloads&limit=40")).let { a ->
    (0 until a.length()).map { a.getJSONObject(it).run { HfModel(getString("id"), optInt("downloads"), optInt("likes")) } } }

  fun files(id: String): List<HfFile> = JSONArray(get("https://huggingface.co/api/models/$id/tree/main")).let { a ->
    (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getString("path").endsWith(".gguf") }
      .map { HfFile(it.getString("path"), it.optLong("size")) } }

  /** Resumable download into models dir. */
  fun download(id: String, f: HfFile, dir: File, progress: (Float) -> Unit): File {
    val out = File(dir.apply { mkdirs() }, f.name.substringAfterLast('/')); val part = File(out.path + ".part")
    if (out.exists()) return out
    val req = Request.Builder().url("https://huggingface.co/$id/resolve/main/${f.name}")
      .apply { if (part.exists()) header("Range", "bytes=${part.length()}-") }.build()
    http.newCall(req).execute().use { r -> r.body!!.byteStream().use { i ->
      java.io.FileOutputStream(part, true).use { o -> val buf = ByteArray(1 shl 16); var n: Int; var done = part.length()
        while (i.read(buf).also { n = it } > 0) { o.write(buf, 0, n); done += n; progress(done.toFloat() / f.size) } } } }
    part.renameTo(out); return out
  }
}
