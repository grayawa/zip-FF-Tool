package com.grayawa.zipff;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 把抢救出来的条目写进 SAF 选中的目录树（不需要任何存储权限）。 */
final class SafTreeSink implements Salvage.Sink {

    private final ContentResolver cr;
    private final Uri rootDir;
    private final Map<String, Uri> dirs = new HashMap<>();

    SafTreeSink(ContentResolver cr, Uri rootDir) {
        this.cr = cr;
        this.rootDir = rootDir;
    }

    Uri ensureDirPublic(String path) throws IOException {
        return ensureDir(path);
    }

    @Override
    public OutputStream open(String path) throws IOException {
        int idx = path.lastIndexOf('/');
        Uri parent = idx < 0 ? rootDir : ensureDir(path.substring(0, idx));
        String name = idx < 0 ? path : path.substring(idx + 1);
        Uri doc = createFile(parent, name);
        if (doc == null) {
            Uri old = findChild(parent, name);
            if (old != null) {
                deleteQuietly(old);
                doc = createFile(parent, name);
            }
        }
        if (doc == null) {
            throw new IOException("无法创建文件 " + path);
        }
        OutputStream os = cr.openOutputStream(doc, "wt");
        if (os == null) {
            throw new IOException("无法写入文件 " + path);
        }
        return os;
    }

    @Override
    public void delete(String path) {
        try {
            int idx = path.lastIndexOf('/');
            Uri parent = idx < 0 ? rootDir : ensureDir(path.substring(0, idx));
            Uri doc = findChild(parent, idx < 0 ? path : path.substring(idx + 1));
            if (doc != null) {
                deleteQuietly(doc);
            }
        } catch (Exception ignored) {
            // 删除失败不影响其它条目
        }
    }

    private Uri createFile(Uri parent, String name) {
        try {
            return DocumentsContract.createDocument(cr, parent, mimeOf(name), name);
        } catch (Exception e) {
            return null;
        }
    }

    private void deleteQuietly(Uri doc) {
        try {
            DocumentsContract.deleteDocument(cr, doc);
        } catch (Exception ignored) {
            // 忽略
        }
    }

    private Uri ensureDir(String dirPath) throws IOException {
        Uri cached = dirs.get(dirPath);
        if (cached != null) {
            return cached;
        }
        int idx = dirPath.lastIndexOf('/');
        Uri parent = idx < 0 ? rootDir : ensureDir(dirPath.substring(0, idx));
        String name = idx < 0 ? dirPath : dirPath.substring(idx + 1);
        Uri child = findChild(parent, name);
        if (child == null) {
            try {
                child = DocumentsContract.createDocument(
                        cr, parent, DocumentsContract.Document.MIME_TYPE_DIR, name);
            } catch (Exception e) {
                throw new IOException("无法创建目录 " + dirPath, e);
            }
        }
        if (child == null) {
            throw new IOException("无法创建目录 " + dirPath);
        }
        dirs.put(dirPath, child);
        return child;
    }

    private Uri findChild(Uri parent, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                parent, DocumentsContract.getDocumentId(parent));
        Cursor c = null;
        try {
            c = cr.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            while (c != null && c.moveToNext()) {
                if (name.equals(c.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent, c.getString(0));
                }
            }
        } catch (Exception ignored) {
            // 查不到就当不存在
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return null;
    }

    private static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (m != null) {
                return m;
            }
        }
        return "application/octet-stream";
    }
}
