package com.grayawa.zipff

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.io.IOException
import java.io.InputStream
import java.util.Locale

private const val REQ_FILES = 1001
private const val REQ_TREE = 1002

private data class UiState(
    val files: List<Uri> = emptyList(),
    val outLabel: String? = null,
    val running: Boolean = false,
    val status: String = "",
    val log: String = "",
)

class MainActivity : Activity() {

    private var state by mutableStateOf(UiState())
    private var treeUri: Uri? = null
    private val picked = mutableListOf<Uri>()

    private var prefs: SharedPreferences? = null
    private val ui = Handler(Looper.getMainLooper())
    private val logBuf = StringBuilder()
    private var lastFlush = 0L

    @Volatile
    private var cancelRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("zipff", MODE_PRIVATE)
        restoreState()
        handleIntent(intent)
        setContentView(ComposeView(this).apply { setContent { AppUi() } })
        refreshState()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let {
            setIntent(it)
            handleIntent(it)
            refreshState()
        }
    }

    // ---------- Miuix 界面 ----------

    @Composable
    private fun AppUi() {
        MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("zip - FF 修复并解压", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "批量选 zip → 按 zip -FF 的方式抢救坏包 → 解压到指定文件夹",
                    fontSize = 13.sp,
                )

                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { pickFiles() }, modifier = Modifier.fillMaxWidth()) {
                            Text("① 选择 ZIP 文件（可多选）")
                        }
                        Text(filesLabel(), fontSize = 13.sp)
                        Button(onClick = { pickTree() }, modifier = Modifier.fillMaxWidth()) {
                            Text("② 选择输出文件夹")
                        }
                        Text(
                            state.outLabel?.let { "输出到: $it" } ?: "未选择输出文件夹",
                            fontSize = 13.sp,
                        )
                        Button(
                            onClick = {
                                if (state.running) {
                                    cancelRequested = true
                                } else {
                                    startJob()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (state.running) "取消" else "③ 开始修复并解压")
                        }
                    }
                }

                if (state.status.isNotEmpty()) {
                    Text(state.status, fontSize = 13.sp)
                }

                Card(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(4.dp),
                    ) {
                        Text(state.log, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    private fun filesLabel(): String {
        if (picked.isEmpty()) return "未选择文件"
        val shown = picked.take(6).joinToString("\n  ", prefix = "  ") { displayName(it) }
        val more = if (picked.size > 6) "\n  …" else ""
        return "已选 ${picked.size} 个文件：\n$shown$more"
    }

    // ---------- 选择文件 ----------

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
            )
            initialDir()?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_FILES)
    }

    private fun pickTree() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
            initialDir()?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_TREE)
    }

    /** 让选择器直接打开上次用过的目录，省得每次翻。 */
    private fun initialDir(): Uri? {
        val t = treeUri ?: return null
        return try {
            DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
        } catch (_: Exception) {
            null
        }
    }

    @Deprecated("Android 里继续用 startActivityForResult 足够，避免额外依赖")
    override fun onActivityResult(req: Int, result: Int, data: Intent?) {
        super.onActivityResult(req, result, data)
        if (result != RESULT_OK || data == null) return
        when (req) {
            REQ_FILES -> {
                picked.clear()
                val clip = data.clipData
                if (clip != null) {
                    for (k in 0 until clip.itemCount) addFile(clip.getItemAt(k).uri)
                } else {
                    data.data?.let { addFile(it) }
                }
                saveState()
                refreshState()
            }

            REQ_TREE -> {
                val u = data.data ?: return
                treeUri = u
                try {
                    contentResolver.takePersistableUriPermission(
                        u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (_: Exception) {
                    // 某些系统不允许持久化，当前会话仍可写
                }
                saveState()
                refreshState()
            }
        }
    }

    private fun addFile(uri: Uri) {
        if (picked.none { it == uri }) {
            picked.add(uri)
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // 忽略
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { addFile(it) }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val extra = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                extra?.let { addFile(it) }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c: Cursor ->
                    if (c.moveToFirst()) name = c.getString(0)
                }
        } catch (_: Exception) {
            // 退回路径
        }
        return name ?: uri.lastPathSegment ?: uri.toString()
    }

    private fun treeLabel(uri: Uri): String {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val i = docId.indexOf(':')
        return if (i >= 0) docId.substring(i + 1) else docId
    }

    // ---------- 状态持久化 ----------

    private fun saveState() {
        val sb = StringBuilder()
        picked.forEach { sb.append(it).append('\n') }
        prefs?.edit()
            ?.putString("tree_uri", treeUri?.toString() ?: "")
            ?.putString("file_uris", sb.toString())
            ?.apply()
    }

    private fun restoreState() {
        try {
            val t = prefs?.getString("tree_uri", "").orEmpty()
            if (t.isNotEmpty()) treeUri = Uri.parse(t)
            val f = prefs?.getString("file_uris", "").orEmpty()
            f.split('\n').forEach { line ->
                if (line.trim().isNotEmpty()) picked.add(Uri.parse(line.trim()))
            }
        } catch (_: Exception) {
            // 读不出来就当没存过
        }
    }

    private fun refreshState() {
        state = state.copy(
            files = picked.toList(),
            outLabel = treeUri?.let { treeLabel(it) },
        )
    }

    // ---------- 任务 ----------

    private fun startJob() {
        if (picked.isEmpty()) {
            toast("先选至少一个 zip 文件")
            return
        }
        val tree = treeUri
        if (tree == null) {
            toast("先选输出文件夹")
            return
        }
        cancelRequested = false
        logBuf.setLength(0)
        state = state.copy(running = true, status = "正在处理…", log = "")
        val batch = picked.toList()
        Thread({ runJob(batch, tree) }, "zipff-worker").start()
    }

    private fun runJob(batch: List<Uri>, tree: Uri) {
        var okFiles = 0
        var partial = 0
        var failed = 0
        var totalOut = 0L
        var index = 0
        val t0 = System.currentTimeMillis()

        for (uri in batch) {
            if (cancelRequested) break
            index++
            val i = index
            ui.post { state = state.copy(status = "处理中 $i/${batch.size}") }

            val name = displayName(uri)
            log("")
            log("===== [$i/${batch.size}] $name")

            var shown = 0
            val result: Salvage.Result
            try {
                val root = SafTreeSink(contentResolver, tree)
                val sink = root.subdir(stripZip(name))
                val listener = object : Salvage.Listener {
                    override fun isCancelled(): Boolean = cancelRequested

                    override fun onEntryDone(path: String, bytes: Long, ok: Boolean, reason: String?) {
                        if (shown < 500) {
                            shown++
                            log((if (ok) "  ok  " else "  bad ") + path + "  " + human(bytes) +
                                (reason?.let { "  <$it>" } ?: ""))
                        } else if (shown == 500) {
                            shown++
                            log("  … 条目过多，后面只统计")
                        }
                    }

                    override fun onNotice(message: String) {
                        log("  ..  $message")
                    }
                }
                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) throw IOException("打不开文件")
                    result = Salvage(sink, listener).run(input as InputStream)
                }
            } catch (e: Exception) {
                log("  失败: $e")
                failed++
                continue
            }

            totalOut += result.bytesOut
            log("  ${result.summary()}")
            when {
                !result.foundAnything() -> {
                    log("  没找到任何 zip 条目（不是 zip，或已损坏到无法识别）")
                    failed++
                }
                result.damaged > 0 || result.skipped > 0 -> partial++
                else -> okFiles++
            }
        }

        val cost = System.currentTimeMillis() - t0
        log("")
        log(
            "----- 汇总: 已处理 $index/${batch.size} 个文件，完整 $okFiles" +
                "，部分恢复 $partial，失败 $failed，共写出 ${human(totalOut)}" +
                "，用时 ${cost / 1000}s",
        )
        if (cancelRequested) log("（已取消，剩余文件未处理）")

        val statusText = String.format(
            Locale.ROOT,
            "完成 %d 个：完整 %d，部分恢复 %d，失败 %d，写出 %s，用时 %.1fs",
            index, okFiles, partial, failed, human(totalOut), cost / 1000.0,
        )
        flushLog()
        ui.post { state = state.copy(running = false, status = statusText) }
    }

    private fun stripZip(name: String): String {
        if (name.lowercase(Locale.ROOT).endsWith(".zip") && name.length > 4) {
            return name.substring(0, name.length - 4)
        }
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    private fun human(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1fK", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1fM", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.ROOT, "%.2fG", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    // ---------- 日志 ----------

    private fun log(line: String) {
        synchronized(logBuf) { logBuf.append(line).append('\n') }
        val now = System.currentTimeMillis()
        if (now - lastFlush > 150) {
            lastFlush = now
            flushLog()
        }
    }

    private fun flushLog() {
        val text: String
        synchronized(logBuf) {
            if (logBuf.isEmpty()) return
            text = logBuf.toString()
            logBuf.setLength(0)
        }
        ui.post {
            val merged = state.log + text
            state = state.copy(
                log = if (merged.length > 120_000) merged.substring(merged.length - 120_000) else merged,
            )
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        cancelRequested = true
        super.onDestroy()
    }
}
