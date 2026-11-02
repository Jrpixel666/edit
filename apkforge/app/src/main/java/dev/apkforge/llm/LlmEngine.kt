package dev.apkforge.llm
fun interface TokenCb { fun onToken(t: String): Boolean }
object LlmEngine {
  init { System.loadLibrary("forge") }
  var loaded: String? = null; private set
  private external fun nLoad(path: String, ctx: Int, threads: Int): Boolean
  private external fun nFree()
  private external fun nTemplate(roles: Array<String>, contents: Array<String>): String
  private external fun nGenerate(prompt: String, maxTok: Int, temp: Float, cb: TokenCb): String
  @Synchronized fun load(path: String, ctx: Int = 8192): Boolean {
    if (loaded == path) return true; if (loaded != null) nFree()
    return nLoad(path, ctx, Runtime.getRuntime().availableProcessors().coerceAtMost(6)).also { if (it) loaded = path }
  }
  @Synchronized fun chat(msgs: List<Pair<String, String>>, maxTok: Int = 1536, onTok: (String) -> Boolean = { true }) =
    nGenerate(nTemplate(msgs.map { it.first }.toTypedArray(), msgs.map { it.second }.toTypedArray()), maxTok, 0.2f) { onTok(it) }
}
