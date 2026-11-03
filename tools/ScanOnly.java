import com.grayawa.zipff.Salvage;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

/** 只扫描、不落盘：统计一个文件里有多少 zip 条目、能否解出。 */
public final class ScanOnly {
    public static void main(String[] args) throws Exception {
        for (String path : args) {
            final long[] sinkBytes = {0};
            Salvage.Sink sink = new Salvage.Sink() {
                @Override public OutputStream openEntry(String p) {
                    return new OutputStream() {
                        @Override public void write(int b) { sinkBytes[0]++; }
                        @Override public void write(byte[] b, int off, int len) { sinkBytes[0] += len; }
                    };
                }
                @Override public void delete(String p) { }
            };
            final StringBuilder notes = new StringBuilder();
            Salvage.Listener listener = new Salvage.Listener() {
                @Override public boolean isCancelled() { return false; }
                @Override public void onEntryDone(String p, long bytes, boolean ok, String reason) {
                    notes.append("      ").append(ok ? "ok  " : "bad ").append(p)
                         .append(reason == null ? "" : "  <" + reason + ">").append('\n');
                }
                @Override public void onNotice(String m) { }
            };
            long t0 = System.currentTimeMillis();
            Salvage.Result r;
            try (FileInputStream in = new FileInputStream(path)) {
                r = new Salvage(sink, listener).run(in);
            }
            String name = path.substring(path.lastIndexOf('/') + 1);
            System.out.printf("%-46s 救回 %d，损坏 %d，跳过 %d，%dms%n",
                    name, r.ok, r.damaged, r.skipped, System.currentTimeMillis() - t0);
            if (notes.length() > 0) System.out.print(notes);
        }
    }
}
