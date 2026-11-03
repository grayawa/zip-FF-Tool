package com.grayawa.zipff;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {

    private static final int REQ_FILES = 1001;
    private static final int REQ_TREE = 1002;
    private static final int MAX_LOG_ENTRIES = 500;

    private final List<Uri> files = new ArrayList<>();
    private Uri treeUri;

    private TextView filesView;
    private TextView treeView;
    private TextView statusView;
    private TextView logView;
    private ScrollView logScroll;
    private ProgressBar progress;
    private Button startBtn;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder pending = new StringBuilder();
    private long lastFlush;
    private volatile boolean running;
    private volatile boolean cancelRequested;

    private SharedPreferences prefs;

    private static final String PREF = "zipff";
    private static final String KEY_TREE = "tree_uri";
    private static final String KEY_FILES = "file_uris";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        setContentView(buildUi());
        restoreState();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            addFile(intent.getData());
            refreshFiles();
        } else if (Intent.ACTION_SEND.equals(action)) {
            Object extra = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (extra instanceof Uri) {
                addFile((Uri) extra);
                refreshFiles();
            }
        }
    }

    // ---------- 界面 ----------

    private View buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("zip -FF 修复并解压");
        title.setTextSize(22);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("批量选 zip → 按 zip -FF 的方式抢救坏包 → 解压到指定文件夹");
        hint.setTextSize(13);
        hint.setPadding(0, dp(4), 0, dp(12));
        root.addView(hint);

        Button pickFiles = new Button(this);
        pickFiles.setText("① 选择 ZIP 文件（可多选）");
        pickFiles.setOnClickListener(v -> pickFiles());
        root.addView(pickFiles, matchWrap());

        filesView = new TextView(this);
        filesView.setText("未选择文件");
        filesView.setTextSize(13);
        filesView.setPadding(0, dp(4), 0, dp(10));
        root.addView(filesView);

        Button pickTree = new Button(this);
        pickTree.setText("② 选择输出文件夹");
        pickTree.setOnClickListener(v -> pickTree());
        root.addView(pickTree, matchWrap());

        treeView = new TextView(this);
        treeView.setText("未选择输出文件夹");
        treeView.setTextSize(13);
        treeView.setPadding(0, dp(4), 0, dp(10));
        root.addView(treeView);

        startBtn = new Button(this);
        startBtn.setText("③ 开始修复并解压");
        startBtn.setOnClickListener(v -> {
            if (running) {
                cancelRequested = true;
                startBtn.setText("正在取消…");
            } else {
                startJob();
            }
        });
        root.addView(startBtn, matchWrap());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        root.addView(progress, matchWrap());

        statusView = new TextView(this);
        statusView.setText("");
        statusView.setTextSize(13);
        statusView.setPadding(0, dp(4), 0, dp(8));
        root.addView(statusView);

        logScroll = new ScrollView(this);
        logScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setGravity(Gravity.START);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        root.addView(logScroll);

        return root;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ---------- 选择文件 ----------

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-zip-compressed", "application/octet-stream"});
        Uri start = initialDir();
        if (start != null) {
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, start);
        }
        startActivityForResult(i, REQ_FILES);
    }

    private void pickTree() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        Uri start = initialDir();
        if (start != null) {
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, start);
        }
        startActivityForResult(i, REQ_TREE);
    }

    /** 让选择器直接打开上次用过的目录，省得每次翻。 */
    private Uri initialDir() {
        if (treeUri != null) {
            try {
                return DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            } catch (Exception ignored) {
                // 退回默认位置
            }
        }
        return null;
    }

    private void saveState() {
        SharedPreferences.Editor e = prefs.edit();
        e.putString(KEY_TREE, treeUri == null ? "" : treeUri.toString());
        StringBuilder sb = new StringBuilder();
        for (Uri u : files) {
            sb.append(u).append('\n');
        }
        e.putString(KEY_FILES, sb.toString());
        e.apply();
    }

    private void restoreState() {
        try {
            String t = prefs.getString(KEY_TREE, "");
            if (t != null && !t.isEmpty()) {
                treeUri = Uri.parse(t);
                treeView.setText("输出到: " + describeTree(treeUri));
            }
            String f = prefs.getString(KEY_FILES, "");
            if (f != null && !f.isEmpty()) {
                for (String line : f.split("\n")) {
                    if (!line.trim().isEmpty()) {
                        files.add(Uri.parse(line.trim()));
                    }
                }
                refreshFiles();
            }
        } catch (Exception ignored) {
            // 读不出来就当没存过
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (result != RESULT_OK || data == null) {
            return;
        }
        if (req == REQ_FILES) {
            files.clear();
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                for (int k = 0; k < n; k++) {
                    addFile(data.getClipData().getItemAt(k).getUri());
                }
            } else if (data.getData() != null) {
                addFile(data.getData());
            }
            refreshFiles();
            saveState();
        } else if (req == REQ_TREE) {
            treeUri = data.getData();
            if (treeUri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) {
                    // 某些系统不允许持久化，当前会话仍可写
                }
                treeView.setText("输出到: " + describeTree(treeUri));
                saveState();
            }
        }
    }

    private void addFile(Uri uri) {
        if (uri == null) {
            return;
        }
        for (Uri u : files) {
            if (u.equals(uri)) {
                return;
            }
        }
        files.add(uri);
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // 忽略
        }
    }

    private void refreshFiles() {
        if (files.isEmpty()) {
            filesView.setText("未选择文件");
            return;
        }
        StringBuilder sb = new StringBuilder("已选 " + files.size() + " 个文件:");
        int shown = 0;
        for (Uri u : files) {
            if (shown++ >= 8) {
                sb.append("\n  …");
                break;
            }
            sb.append("\n  ").append(displayName(u));
        }
        filesView.setText(sb.toString());
    }

    private String describeTree(Uri uri) {
        String docId = DocumentsContract.getTreeDocumentId(uri);
        int i = docId.indexOf(':');
        return i >= 0 ? docId.substring(i + 1) : docId;
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
            }
        } catch (Exception ignored) {
            // 退回路径
        }
        if (name == null) {
            name = uri.getLastPathSegment();
        }
        return name == null ? uri.toString() : name;
    }

    // ---------- 任务 ----------

    private void startJob() {
        if (files.isEmpty()) {
            toast("先选至少一个 zip 文件");
            return;
        }
        if (treeUri == null) {
            toast("先选输出文件夹");
            return;
        }
        running = true;
        cancelRequested = false;
        startBtn.setText("取消");
        logView.setText("");
        progress.setMax(files.size());
        progress.setProgress(0);
        final List<Uri> batch = new ArrayList<>(files);
        new Thread(() -> runJob(batch), "zipff-worker").start();
    }

    private void runJob(List<Uri> batch) {
        int okFiles = 0;
        int partialFiles = 0;
        int failedFiles = 0;
        long totalOut = 0;
        int index = 0;
        long t0 = System.currentTimeMillis();

        for (Uri uri : batch) {
            if (cancelRequested) {
                break;
            }
            index++;
            final int idx = index;
            ui.post(() -> {
                progress.setProgress(idx);
                statusView.setText("处理中 " + idx + "/" + batch.size());
            });

            String name = displayName(uri);
            String stem = stripZip(name);
            logLine("");
            logLine("===== [" + index + "/" + batch.size() + "] " + name);

            SafTreeSink root = new SafTreeSink(getContentResolver(), treeUri);
            final int[] shown = {0};
            Salvage.Result r;
            try {
                SafTreeSink sink = root.subdir(stem);
                Salvage.Listener listener = new Salvage.Listener() {
                    @Override
                    public boolean isCancelled() {
                        return cancelRequested;
                    }

                    @Override
                    public void onEntryDone(String path, long bytes, boolean ok, String reason) {
                        if (shown[0] < MAX_LOG_ENTRIES) {
                            shown[0]++;
                            logLine((ok ? "  ok  " : "  bad ") + path + "  " + human(bytes)
                                    + (reason == null ? "" : "  <" + reason + ">"));
                        } else if (shown[0] == MAX_LOG_ENTRIES) {
                            shown[0]++;
                            logLine("  … 条目过多，后面只统计");
                        }
                    }

                    @Override
                    public void onNotice(String message) {
                        logLine("  ..  " + message);
                    }
                };
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) {
                        throw new IOException("打不开文件");
                    }
                    r = new Salvage(sink, listener).run(in);
                }
            } catch (Exception e) {
                logLine("  失败: " + e);
                failedFiles++;
                continue;
            }

            totalOut += r.bytesOut;
            logLine("  " + r.summary());
            if (!r.foundAnything()) {
                logLine("  没找到任何 zip 条目（不是 zip，或已损坏到无法识别）");
                failedFiles++;
            } else if (r.damaged > 0 || r.skipped > 0) {
                partialFiles++;
            } else {
                okFiles++;
            }
        }

        final long cost = System.currentTimeMillis() - t0;
        final int fOk = okFiles;
        final int fPart = partialFiles;
        final int fFail = failedFiles;
        final long fOut = totalOut;
        final int fIdx = index;
        final int total = batch.size();
        logLine("");
        logLine("----- 汇总: 已处理 " + fIdx + "/" + total + " 个文件，完整 " + fOk
                + "，部分恢复 " + fPart + "，失败 " + fFail
                + "，共写出 " + human(fOut) + "，用时 " + (cost / 1000) + "s");
        if (cancelRequested) {
            logLine("（已取消，剩余文件未处理）");
        }
        flushLog();

        ui.post(() -> {
            running = false;
            startBtn.setText("③ 开始修复并解压");
            statusView.setText(String.format(Locale.ROOT,
                    "完成 %d 个：完整 %d，部分恢复 %d，失败 %d，写出 %s，用时 %.1fs",
                    fIdx, fOk, fPart, fFail, human(fOut), cost / 1000.0));
        });
    }

    private String stripZip(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip") && name.length() > 4) {
            return name.substring(0, name.length() - 4);
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        double k = bytes / 1024.0;
        if (k < 1024) {
            return String.format(Locale.ROOT, "%.1fK", k);
        }
        double m = k / 1024.0;
        if (m < 1024) {
            return String.format(Locale.ROOT, "%.1fM", m);
        }
        return String.format(Locale.ROOT, "%.2fG", m / 1024.0);
    }

    // ---------- 日志 ----------

    private void logLine(String s) {
        synchronized (pending) {
            pending.append(s).append('\n');
        }
        long now = System.currentTimeMillis();
        if (now - lastFlush > 150) {
            lastFlush = now;
            flushLog();
        }
    }

    private void flushLog() {
        final String text;
        synchronized (pending) {
            if (pending.length() == 0) {
                return;
            }
            text = pending.toString();
            pending.setLength(0);
        }
        ui.post(() -> {
            String merged = logView.getText() + text;
            if (merged.length() > 300_000) {
                merged = merged.substring(merged.length() - 300_000);
            }
            logView.setText(merged);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        cancelRequested = true;
        super.onDestroy();
    }
}
