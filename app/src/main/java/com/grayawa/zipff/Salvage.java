package com.grayawa.zipff;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * 顺序扫描式 zip 抢救解压。
 *
 * 行为对标 Info-ZIP `zip -FF`（"salvage what can"）：不依赖可能损坏的中央目录，
 * 直接在字节流里寻找本地文件头 PK\3\4，逐个解出条目；某个条目坏了就丢弃它并从
 * 当前位置继续向后找下一个头，因此损坏位置之后的好文件依然能救出来。
 */
public final class Salvage {

    /** 输出目标，由调用方决定写到哪里（Android 上是 SAF 目录，本地测试是文件系统）。 */
    public interface Sink {
        OutputStream open(String path) throws IOException;

        void delete(String path);
    }

    public interface Listener {
        boolean isCancelled();

        void onEntryDone(String path, long bytes, boolean ok, String reason);

        void onNotice(String message);
    }

    public static final class Result {
        public int ok;
        public int damaged;
        public int skipped;
        public long bytesOut;

        public boolean foundAnything() {
            return ok > 0 || damaged > 0 || skipped > 0;
        }

        public String summary() {
            return "救回 " + ok + " 个条目，损坏 " + damaged + " 个，跳过 " + skipped
                    + " 个，共写出 " + bytesOut + " 字节";
        }
    }

    private final Sink sink;
    private final Listener listener;

    private final byte[] chunk = new byte[1 << 16];
    private final byte[] obuf = new byte[1 << 16];
    private final byte[] hdr = new byte[26];
    private final byte[] nameBuf = new byte[4096];
    private final Inflater inflater = new Inflater(true);

    public Salvage(Sink sink, Listener listener) {
        this.sink = sink;
        this.listener = listener;
    }

    public Result run(InputStream raw) throws IOException {
        Result res = new Result();
        Buf buf = new Buf(raw);
        try {
            while (true) {
                if (listener != null && listener.isCancelled()) {
                    break;
                }
                if (!seekHeader(buf)) {
                    break;
                }
                if (!buf.readFully(hdr, hdr.length)) {
                    break;
                }
                int flags = u16(hdr, 2);
                int method = u16(hdr, 4);
                long crcHeader = u32(hdr, 10);
                long csize = u32(hdr, 14);
                long usize = u32(hdr, 18);
                int nameLen = u16(hdr, 22);
                int extraLen = u16(hdr, 24);

                // 合理性检查：不像本地文件头就当作误命中的字节序列，继续向后找
                if (nameLen == 0 || nameLen > nameBuf.length || (method != 0 && method != 8)) {
                    continue;
                }
                if (!buf.readFully(nameBuf, nameLen)) {
                    break;
                }
                byte[] extra = new byte[extraLen];
                if (extraLen > 0 && !buf.readFully(extra, extraLen)) {
                    break;
                }

                // ZIP64：真实大小在扩展字段 0x0001 里
                if (csize == 0xFFFFFFFFL || usize == 0xFFFFFFFFL) {
                    long[] z64 = parseZip64(extra, extraLen, usize == 0xFFFFFFFFL, csize == 0xFFFFFFFFL);
                    if (z64 != null) {
                        usize = z64[0];
                        csize = z64[1];
                    }
                }

                String rawName = decodeName(nameBuf, nameLen, flags);
                if (rawName.endsWith("/")) {
                    skipData(buf, flags, csize);
                    continue;
                }
                String path = sanitize(rawName);
                if (path == null) {
                    skipData(buf, flags, csize);
                    res.skipped++;
                    if (listener != null) {
                        listener.onEntryDone(rawName, 0, false, "路径不安全，已跳过");
                    }
                    continue;
                }
                if ((flags & 1) != 0) {
                    skipData(buf, flags, csize);
                    res.skipped++;
                    if (listener != null) {
                        listener.onEntryDone(path, 0, false, "条目已加密，无法解出");
                    }
                    continue;
                }

                EntryOutcome eo = new EntryOutcome();
                boolean good = method == 8
                        ? extractDeflate(buf, path, crcHeader, usize, flags, eo)
                        : extractStored(buf, path, crcHeader, csize, eo);

                if (!good) {
                    sink.delete(path);
                    res.damaged++;
                } else {
                    res.ok++;
                    res.bytesOut += eo.written;
                }
                if (listener != null) {
                    listener.onEntryDone(path, eo.written, good, eo.reason);
                }
            }
        } finally {
            inflater.end();
        }
        return res;
    }

    private static final class EntryOutcome {
        long written;
        String reason;
    }

    private boolean extractDeflate(Buf buf, String path, long crcHeader, long usize,
                                   int flags, EntryOutcome eo) throws IOException {
        OutputStream out = sink.open(path);
        CRC32 crc = new CRC32();
        boolean good = true;
        int lastFed = 0;
        try {
            inflater.reset();
            while (true) {
                if (listener != null && listener.isCancelled()) {
                    good = false;
                    eo.reason = "已取消";
                    break;
                }
                if (inflater.needsInput()) {
                    int n = buf.read(chunk, 0, chunk.length);
                    if (n <= 0) {
                        good = false;
                        eo.reason = "数据提前结束";
                        break;
                    }
                    inflater.setInput(chunk, 0, n);
                    lastFed = n;
                }
                int k;
                try {
                    k = inflater.inflate(obuf, 0, obuf.length);
                } catch (DataFormatException e) {
                    good = false;
                    eo.reason = "压缩数据损坏";
                    break;
                }
                if (k > 0) {
                    out.write(obuf, 0, k);
                    crc.update(obuf, 0, k);
                    eo.written += k;
                } else if (inflater.finished()) {
                    break;
                } else if (inflater.needsDictionary()) {
                    good = false;
                    eo.reason = "缺少解压字典";
                    break;
                } else {
                    good = false;
                    eo.reason = "解压停滞";
                    break;
                }
            }
            if (good) {
                int rem = inflater.getRemaining();
                if (rem > 0 && lastFed > rem) {
                    buf.pushback(chunk, lastFed - rem, rem);
                } else if (rem > 0 && lastFed == rem) {
                    // 整个缓冲区都没消耗，理论上不会发生
                    buf.pushback(chunk, 0, rem);
                }
                good = verify(crcHeader, usize, flags, crc.getValue(), eo);
            }
        } finally {
            out.close();
        }
        return good;
    }

    private boolean extractStored(Buf buf, String path, long crcHeader, long csize,
                                  EntryOutcome eo) throws IOException {
        if (csize <= 0 || csize == 0xFFFFFFFFL) {
            eo.reason = "存储模式条目长度未知";
            return false;
        }
        OutputStream out = sink.open(path);
        CRC32 crc = new CRC32();
        try {
            long left = csize;
            while (left > 0) {
                if (listener != null && listener.isCancelled()) {
                    eo.reason = "已取消";
                    return false;
                }
                int want = (int) Math.min(left, chunk.length);
                int n = buf.read(chunk, 0, want);
                if (n <= 0) {
                    eo.reason = "数据提前结束";
                    return false;
                }
                out.write(chunk, 0, n);
                crc.update(chunk, 0, n);
                eo.written += n;
                left -= n;
            }
            return verify(crcHeader, -1, 0, crc.getValue(), eo);
        } finally {
            out.close();
        }
    }

    private static boolean verify(long crcHeader, long usize, int flags,
                                  long crcActual, EntryOutcome eo) {
        if ((flags & 8) == 0) {
            if (crcHeader != 0 && crcHeader != crcActual) {
                eo.reason = "CRC 校验失败";
                return false;
            }
            if (usize >= 0 && usize != 0xFFFFFFFFL && usize != eo.written) {
                eo.reason = "解出长度与头信息不符";
                return false;
            }
        }
        return true;
    }

    private void skipData(Buf buf, int flags, long csize) throws IOException {
        if ((flags & 8) != 0 || csize <= 0 || csize == 0xFFFFFFFFL) {
            return;
        }
        long left = csize;
        while (left > 0) {
            int n = buf.read(chunk, 0, (int) Math.min(left, chunk.length));
            if (n <= 0) {
                return;
            }
            left -= n;
        }
    }

    private static boolean seekHeader(Buf buf) throws IOException {
        int state = 0;
        while (true) {
            int c = buf.read();
            if (c < 0) {
                return false;
            }
            switch (state) {
                case 0:
                    state = c == 'P' ? 1 : 0;
                    break;
                case 1:
                    state = c == 'K' ? 2 : (c == 'P' ? 1 : 0);
                    break;
                case 2:
                    state = c == 0x03 ? 3 : (c == 'P' ? 1 : 0);
                    break;
                default:
                    if (c == 0x04) {
                        return true;
                    }
                    state = c == 'P' ? 1 : 0;
                    break;
            }
        }
    }

    private static long[] parseZip64(byte[] extra, int extraLen, boolean wantU, boolean wantC) {
        int i = 0;
        while (i + 4 <= extraLen) {
            int id = u16(extra, i);
            int size = u16(extra, i + 2);
            int body = i + 4;
            if (id == 0x0001) {
                long u = 0xFFFFFFFFL;
                long c = 0xFFFFFFFFL;
                int p = body;
                if (wantU && p + 8 <= body + size && p + 8 <= extraLen) {
                    u = u64(extra, p);
                    p += 8;
                }
                if (wantC && p + 8 <= body + size && p + 8 <= extraLen) {
                    c = u64(extra, p);
                }
                return new long[]{u, c};
            }
            i = body + size;
        }
        return null;
    }

    private static String decodeName(byte[] raw, int len, int flags) {
        boolean utf8 = (flags & 0x800) != 0;
        if (!utf8) {
            String s = tryDecode(raw, len, StandardCharsets.UTF_8);
            if (s != null) {
                return s;
            }
            s = tryDecode(raw, len, Charset.forName("GBK"));
            if (s != null) {
                return s;
            }
        }
        String s = tryDecode(raw, len, StandardCharsets.UTF_8);
        return s != null ? s : new String(raw, 0, len, StandardCharsets.ISO_8859_1);
    }

    private static String tryDecode(byte[] raw, int len, Charset cs) {
        try {
            return cs.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw, 0, len))
                    .toString();
        } catch (CharacterCodingException | UnsupportedOperationException e) {
            return null;
        }
    }

    /** 去掉绝对路径、上跳目录和盘符，避免写到解压目录之外。 */
    static String sanitize(String name) {
        if (name == null) {
            return null;
        }
        String s = name.replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        if (s.length() >= 2 && s.charAt(1) == ':') {
            s = s.substring(2);
            while (s.startsWith("/")) {
                s = s.substring(1);
            }
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (String part : s.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                return null;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(part);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xff))
                | ((long) (b[off + 1] & 0xff) << 8)
                | ((long) (b[off + 2] & 0xff) << 16)
                | ((long) (b[off + 3] & 0xff) << 24);
    }

    private static long u64(byte[] b, int off) {
        return u32(b, off) | (u32(b, off + 4) << 32);
    }

    /** 只向前读的缓冲流，支持把解压器没吃完的尾巴塞回去。 */
    private static final class Buf {
        private final InputStream in;
        private final byte[] b = new byte[1 << 16];
        private int pos;
        private int lim;
        private byte[] pb;
        private int pbPos;
        private int pbLen;

        Buf(InputStream in) {
            this.in = in;
        }

        int read() throws IOException {
            if (pb != null) {
                if (pbPos < pbLen) {
                    return pb[pbPos++] & 0xff;
                }
                pb = null;
            }
            if (pos >= lim) {
                lim = in.read(b, 0, b.length);
                pos = 0;
                if (lim <= 0) {
                    lim = 0;
                    return -1;
                }
            }
            return b[pos++] & 0xff;
        }

        int read(byte[] dst, int off, int len) throws IOException {
            int n = 0;
            while (n < len) {
                if (pb != null && pbPos < pbLen) {
                    dst[off + n++] = pb[pbPos++];
                    continue;
                }
                pb = null;
                if (pos >= lim) {
                    lim = in.read(b, 0, b.length);
                    pos = 0;
                    if (lim <= 0) {
                        lim = 0;
                        break;
                    }
                }
                int take = Math.min(len - n, lim - pos);
                System.arraycopy(b, pos, dst, off + n, take);
                pos += take;
                n += take;
            }
            return n == 0 ? -1 : n;
        }

        boolean readFully(byte[] dst, int len) throws IOException {
            int n = 0;
            while (n < len) {
                int k = read(dst, n, len - n);
                if (k <= 0) {
                    return false;
                }
                n += k;
            }
            return true;
        }

        void pushback(byte[] src, int off, int len) {
            pb = new byte[len];
            System.arraycopy(src, off, pb, 0, len);
            pbPos = 0;
            pbLen = len;
        }
    }
}
