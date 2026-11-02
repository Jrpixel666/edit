package dev.apkforge.agent
import dev.apkforge.core.Workspace
import dev.apkforge.llm.LlmEngine
import org.json.JSONObject
import java.io.File

/**
 * Chat agent: you say "change the launcher's dock to 6 icons and make the background black",
 * the local model calls tools until it replies with "done".
 */
class Agent(private val ws: Workspace, private val project: File, private val emit: (String, String) -> Unit) {
  private val src = File(project, "src")
  private val history = mutableListOf("system" to SYSTEM)

  fun send(user: String) {
    history += "user" to "Project: ${project.name}\nTop-level: ${src.list()?.joinToString()}\n\n$user"
    repeat(25) {
      val sb = StringBuilder(); emit("assistant", "")
      val reply = LlmEngine.chat(history) { sb.append(it); emit("stream", sb.toString()); true }
      history += "assistant" to reply
      val call = Regex("```tool\\s*(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL).find(reply)?.groupValues?.get(1)
        ?: return  // plain answer → finished
      val result = runCatching { exec(JSONObject(call)) }.getOrElse { "ERROR: ${it.message}" }.take(6000)
      emit("tool", result); history += "user" to "TOOL RESULT:\n$result"
    }
  }

  private fun f(p: String) = File(src, p).canonicalFile.also { require(it.path.startsWith(src.canonicalPath)) { "path escapes project" } }

  private fun exec(j: JSONObject): String = when (j.getString("tool")) {
    "list" -> f(j.optString("path", ".")).walkTopDown().maxDepth(j.optInt("depth", 2)).drop(1)
                .joinToString("\n") { it.relativeTo(src).path + if (it.isDirectory) "/" else "" }.take(4000)
    "search" -> src.walkTopDown().filter { it.isFile && it.length() < 2_000_000 && (it.extension in setOf("smali","xml","json","yml","txt","js","html")) }
                .flatMap { file -> file.readLines().withIndex().filter { it.value.contains(j.getString("text"), true) }
                  .map { "${file.relativeTo(src)}:${it.index + 1}: ${it.value.trim()}" } }.take(60).joinToString("\n").ifEmpty { "no matches" }
    "read" -> f(j.getString("path")).readLines().let { l -> val s = j.optInt("from", 1) - 1
                l.drop(s).take(j.optInt("lines", 200)).mapIndexed { i, t -> "${s + i + 1}| $t" }.joinToString("\n") }
    "replace" -> f(j.getString("path")).let { file -> val t = file.readText(); val old = j.getString("old")
                require(t.split(old).size == 2) { "old text must occur exactly once (found ${t.split(old).size - 1})" }
                file.writeText(t.replace(old, j.getString("new"))); "ok" }
    "write" -> f(j.getString("path")).apply { parentFile?.mkdirs(); writeText(j.getString("content")) }.let { "wrote ${it.length()} bytes" }
    "shell" -> StringBuilder().also { sb -> ws.shell.run(j.getString("cmd"), project) { sb.appendLine(it) } }.toString()
    "build" -> ws.build(project) { }.let { "built ${it.name} (${it.length() / 1024} KB)" }
    else -> "unknown tool"
  }

  companion object { val SYSTEM = """
You are ApkForge, an offline Android reverse-engineering/modding agent working on a decoded APK (apktool layout:
AndroidManifest.xml, res/, smali*/, assets/). To act, reply with exactly ONE block:
```tool
{"tool":"NAME", ...args}
```
Tools: list{path,depth} · search{text} · read{path,from,lines} · replace{path,old,new} · write{path,content}
· shell{cmd} (Debian with apktool,aapt2,zipalign,apksigner,jadx,smali,java,python3) · build{}
Rules: search before editing; prefer small `replace` edits; keep smali register counts (.locals) valid;
colors/strings/dimens live in res/values*. After edits, call build and fix any errors it reports.
When finished reply in plain text (no tool block) summarising what changed.""".trimIndent() }
}
