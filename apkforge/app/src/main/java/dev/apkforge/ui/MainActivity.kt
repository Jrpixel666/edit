package dev.apkforge.ui
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apkforge.agent.Agent
import dev.apkforge.core.*
import dev.apkforge.llm.*
import kotlinx.coroutines.*
import java.io.File

class MainActivity : ComponentActivity() {
  lateinit var ws: Workspace
  // SupervisorJob: one failed background op (a bad download, a broken import, a crashed chat
  // turn) must not cancel this shared scope's Job and silently kill every later io.launch{} —
  // including the Chat tab's Send button — for the rest of the app's lifetime.
  private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  override fun onCreate(s: Bundle?) {
    super.onCreate(s); ws = Workspace(this, Shell(this))
    androidx.core.content.ContextCompat.registerReceiver(this, Installer.receiver { log += it },
      android.content.IntentFilter(Installer.ACTION), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    intent?.let(::handleShare)
  }
  override fun onNewIntent(i: Intent) { super.onNewIntent(i); handleShare(i) }
  private val log = mutableStateListOf<String>()
  private var current by mutableStateOf<File?>(null)
  private fun handleShare(i: Intent) {
    val uri = (if (i.action == Intent.ACTION_SEND) i.getParcelableExtra(Intent.EXTRA_STREAM) else i.data) as? Uri ?: return
    importUri(uri)
  }
  private fun importUri(uri: Uri) = io.launch {
    runCatching {
      val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "app.apk"
      ws.import(name, contentResolver.openInputStream(uri)!!) { log += it }
    }.onSuccess { current = it }.onFailure { log += "✘ ${it.message}" }
  }
  private fun install(apk: File) = io.launch {
    val splits = apk.parentFile!!.let { File(it, "splits").listFiles()?.sortedBy { f -> f.name } ?: emptyList() }
    log += "installing base" + if (splits.isNotEmpty()) " + ${splits.size} splits" else ""
    runCatching { Installer.install(this@MainActivity, apk, splits) }.onFailure { log += "✘ ${it.message}" }
  }

  @Composable fun App() {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(bottomBar = { NavigationBar {
      listOf("Projects", "Files", "Chat", "Models", "Terminal").forEachIndexed { i, t ->
        NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(t) }) } } }) { pad ->
      Box(Modifier.padding(pad).fillMaxSize()) { when (tab) { 0 -> Projects(); 1 -> Files(); 2 -> Chat(); 3 -> Models(); 4 -> Terminal() } }
    }
  }

  @Composable fun Projects() {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::importUri) }
    var list by remember { mutableStateOf(ws.projects()) }
    LaunchedEffect(current) { list = ws.projects() }
    Column(Modifier.padding(12.dp)) {
      Row { Button({ pick.launch(arrayOf("*/*")) /* .apk .apks .xapk .apkm */ }) { Text("Import APK") }
        Spacer(Modifier.width(8.dp))
        current?.let { p -> Button({ io.launch { runCatching { ws.build(p) { log += it } }.onSuccess { runOnUiThread { install(it) } }
          .onFailure { log += "✘ ${it.message}" } } }) { Text("Rebuild & install") } } }
      current?.let { p -> val sp = ws.splits(p); if (sp.isNotEmpty()) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Split app: base + ${sp.size} (${sp.joinToString { it.nameWithoutExtension.removePrefix("split_config.") }})", Modifier.weight(1f), fontSize = 12.sp)
        TextButton({ io.launch { runCatching { ws.merge(p) { log += it } }.onFailure { log += "✘ ${it.message}" }; current = p } }) { Text("Merge to single APK") } } }
      Text("Active: ${current?.name ?: "none"}", Modifier.padding(vertical = 8.dp))
      list.forEach { p -> Text(p.name, Modifier.fillMaxWidth().clickable { current = p }.padding(10.dp)) }
      HorizontalDivider(); LogView(log)
    }
  }

  @Composable fun Files() {
    val proj = current ?: return Text("Import or select a project first", Modifier.padding(16.dp))
    var dir by remember(proj) { mutableStateOf(File(proj, "src")) }
    var open by remember { mutableStateOf<File?>(null) }
    var text by remember(open) { mutableStateOf(open?.takeIf { it.length() < 3_000_000 }?.readText() ?: "") }
    open?.let { f -> Column(Modifier.padding(8.dp)) {
      Row { Button({ f.writeText(text); open = null }) { Text("Save") }; Spacer(Modifier.width(8.dp)); OutlinedButton({ open = null }) { Text("Close") } }
      Text(f.relativeTo(proj).path, style = MaterialTheme.typography.labelSmall)
      BasicTextField(text, { text = it }, Modifier.fillMaxSize(), textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground))
    }; return }
    LazyColumn(Modifier.padding(8.dp)) {
      if (dir != File(proj, "src")) item { Text("⬆ ..", Modifier.fillMaxWidth().clickable { dir = dir.parentFile!! }.padding(8.dp)) }
      items(dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()) { f ->
        Text((if (f.isDirectory) "📁 " else "📄 ") + f.name, Modifier.fillMaxWidth().clickable { if (f.isDirectory) dir = f else open = f }.padding(8.dp)) }
    }
  }

  private val chat = mutableStateListOf<Pair<String, String>>()
  @Composable fun Chat() {
    var input by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
      if (LlmEngine.loaded == null) Text("Load a model in the Models tab first", color = MaterialTheme.colorScheme.error)
      LazyColumn(Modifier.weight(1f), reverseLayout = true) { items(chat.reversed()) { (r, t) ->
        Card(Modifier.fillMaxWidth().padding(4.dp)) { Text("$r\n$t", Modifier.padding(8.dp), fontSize = 13.sp,
          fontFamily = if (r == "tool") FontFamily.Monospace else FontFamily.Default) } } }
      Row { OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("e.g. make the dock hold 6 icons") })
        Button({ val proj = current
          if (proj == null) { chat += "tool" to "✘ select a project in the Projects tab first"; return@Button }
          val msg = input; input = ""; busy = true; chat += "you" to msg
          io.launch {
            runCatching { Agent(ws, proj) { role, t -> runOnUiThread {
                if (role == "stream") chat[chat.lastIndex] = "assistant" to t else chat += role to t } }.send(msg) }
              .onFailure { e -> runOnUiThread { chat += "tool" to "✘ ${e.message}" } }
            runOnUiThread { busy = false }
          }
        }, enabled = !busy && LlmEngine.loaded != null) { Text(if (busy) "…" else "Send") } }
    }
  }

  @Composable fun Models() {
    val dir = File(filesDir, "models")
    var q by remember { mutableStateOf("qwen2.5-coder 7b instruct gguf") }
    var res by remember { mutableStateOf(listOf<HfModel>()) }; var files by remember { mutableStateOf(listOf<HfFile>()) }
    var sel by remember { mutableStateOf("") }; var prog by remember { mutableFloatStateOf(0f) }; var status by remember { mutableStateOf("") }
    LazyColumn(Modifier.padding(8.dp)) {
      item { Text("On device", style = MaterialTheme.typography.titleMedium) }
      items(dir.listFiles { f -> f.extension == "gguf" }?.toList() ?: emptyList()) { f ->
        Text("${if (LlmEngine.loaded == f.path) "● " else "○ "}${f.name}  (${f.length() shr 20} MB)",
          Modifier.fillMaxWidth().clickable { status = "loading…"
            io.launch { status = runCatching { LlmEngine.load(f.path) }.getOrElse { false }.let { if (it) "loaded ${f.name}" else "load failed" } } }.padding(8.dp)) }
      item { Text(status); HorizontalDivider()
        Row { OutlinedTextField(q, { q = it }, Modifier.weight(1f), label = { Text("Search Hugging Face") })
          Button({ io.launch { res = runCatching { HuggingFace.search(q) }.getOrDefault(emptyList()); files = emptyList() } }) { Text("Go") } } }
      if (files.isEmpty()) items(res) { m -> Text("${m.id}  ⬇${m.downloads}", Modifier.fillMaxWidth().clickable { sel = m.id
          io.launch { files = runCatching { HuggingFace.files(m.id) }.getOrDefault(emptyList()) } }.padding(8.dp)) }
      else { item { Text(sel, style = MaterialTheme.typography.titleSmall); if (prog > 0) LinearProgressIndicator({ prog }, Modifier.fillMaxWidth()) }
        items(files) { f -> Text("${f.name}  ${f.size shr 20} MB", Modifier.fillMaxWidth().clickable {
          io.launch { runCatching { HuggingFace.download(sel, f, dir) { prog = it } }
            .onSuccess { status = "downloaded ${f.name}" }.onFailure { status = "✘ ${it.message}" }
            prog = 0f } }.padding(8.dp)) } }
    }
  }

  @Composable fun Terminal() {
    val out = remember { mutableStateListOf<String>() }; var cmd by remember { mutableStateOf("") }
    val pickRootfs = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u ?: return@rememberLauncherForActivityResult
      io.launch { runCatching { val t = File(cacheDir, "rootfs.tar.xz"); contentResolver.openInputStream(u)!!.use { i -> t.outputStream().use { i.copyTo(it) } }
        ws.shell.bootstrap(t) { out += it } }.onFailure { out += "✘ ${it.message}" } } }
    Column(Modifier.padding(8.dp)) {
      if (!ws.shell.ready) Row { Button({ io.launch { runCatching {
          val t = File(cacheDir, "rootfs.tar.xz"); out += "downloading Debian rootfs…"
          okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(ROOTFS_URL).build()).execute().use { r -> t.outputStream().use { r.body!!.byteStream().copyTo(it) } }
          ws.shell.bootstrap(t) { out += it } }.onFailure { out += "✘ ${it.message}" } } }) { Text("Install toolchain") }
        Spacer(Modifier.width(8.dp)); OutlinedButton({ pickRootfs.launch(arrayOf("*/*")) }) { Text("From file (offline)") } }
      LogView(out, Modifier.weight(1f))
      Row { OutlinedTextField(cmd, { cmd = it }, Modifier.weight(1f), textStyle = TextStyle(fontFamily = FontFamily.Monospace))
        Button({ val c = cmd; cmd = ""; out += "$ $c"
          io.launch { runCatching { ws.shell.run(c, current) { out += it } }.onFailure { out += "✘ ${it.message}" } } }) { Text("Run") } }
    }
  }

  @Composable fun LogView(lines: List<String>, m: Modifier = Modifier) = LazyColumn(m.fillMaxWidth(), reverseLayout = true) {
    items(lines.reversed()) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) } }

  companion object {
    // proot-distro's Debian arm64 rootfs; swap for any arm64 Debian/Ubuntu tarball
    const val ROOTFS_URL = "https://github.com/termux/proot-distro/releases/download/v4.18.0/debian-bookworm-aarch64-pd-v4.18.0.tar.xz"
  }
}
