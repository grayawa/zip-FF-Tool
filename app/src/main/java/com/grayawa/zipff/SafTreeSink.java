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

/**
 * 把抢救出来的条目写进 SAF 选中的目录树（不需要任何存储权限）。
 *
 * 注意: DocumentsContract 的 createDocument / deleteDocument / 子项查询都要求
 * URI 里带着 tree 段，直接用 createDocument 返回的普通 document URI 再操作子项
 * 会抛 IllegalArgumentException("Invalid URI")。所以这里只保存 documentId，
 * 每次操作都用 buildDocumentUriUsingTree(treeUri, docId) 重新组装。
 */
final class SafTreeSink implements Salvage.Sink {

    private final ContentResolver cr;
    private final Uri treeUri;
    private final String rootDocId;
    private final Map<String, String> dirIds = new HashMap<>();

    SafTreeSink(ContentResolver cr, Uri treeUri) {
        this(cr, treeUri, DocumentsContract.getTreeDocumentId(treeUri));
    }

    private SafTreeSink(ContentResolver cr, Uri treeUri, String rootDocId) {
        this.cr = cr;
        this.treeUri = treeUri;
        this.rootDocId = rootDocId;
    }

    /** 在根目录下准备好这个子目录，返回以它为根的 sink。 */
    SafTreeSink subdir(String name) throws IOException {
        return new SafTreeSink(cr, treeUri, ensureDir(name));
    }

    @Override
    public OutputStream openEntry(String path) throws IOException {
        int idx = path.lastIndexOf('/');
        String parentId = idx < 0 ? rootDocId : ensureDir(path.substring(0, idx));
        String name = idx < 0 ? path : path.substring(idx + 1);

        Uri created = create(parentId, mimeOf(name), name);
        if (created == null) {
            String existing = findChild(parentId, name);
            if (existing != null) {
                deleteQuietly(existing);
                created = create(parentId, mimeOf(name), name);
            }
        }
        if (created == null) {
            throw new IOException("无法创建文件 " + path);
        }
        OutputStream os = cr.openOutputStream(created, "wt");
        if (os == null) {
            throw new IOException("无法写入文件 " + path);
        }
        return os;
    }

    @Override
    public void delete(String path) {
        try {
            int idx = path.lastIndexOf('/');
            String parentId = idx < 0 ? rootDocId : ensureDir(path.substring(0, idx));
            String docId = findChild(parentId, idx < 0 ? path : path.substring(idx + 1));
            if (docId != null) {
                deleteQuietly(docId);
            }
        } catch (Exception ignored) {
            // 删除失败不影响其它条目
        }
    }

    private Uri create(String parentDocId, String mime, String name) {
        try {
            return DocumentsContract.createDocument(cr, docUri(parentDocId), mime, name);
        } catch (Exception e) {
            return null;
        }
    }

    private void deleteQuietly(String docId) {
        try {
            DocumentsContract.deleteDocument(cr, docUri(docId));
        } catch (Exception ignored) {
            // 忽略
        }
    }

    private String ensureDir(String dirPath) throws IOException {
        String cached = dirIds.get(dirPath);
        if (cached != null) {
            return cached;
        }
        int idx = dirPath.lastIndexOf('/');
        String parentId = idx < 0 ? rootDocId : ensureDir(dirPath.substring(0, idx));
        String name = idx < 0 ? dirPath : dirPath.substring(idx + 1);

        String childId = findChild(parentId, name);
        if (childId == null) {
            Uri created = create(parentId, DocumentsContract.Document.MIME_TYPE_DIR, name);
            if (created == null) {
                throw new IOException("无法创建目录 " + dirPath);
            }
            childId = DocumentsContract.getDocumentId(created);
        }
        dirIds.put(dirPath, childId);
        return childId;
    }

    private String findChild(String parentDocId, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        Cursor c = null;
        try {
            c = cr.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            while (c != null && c.moveToNext()) {
                if (name.equals(c.getString(1))) {
                    return c.getString(0);
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

    private Uri docUri(String docId) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
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
