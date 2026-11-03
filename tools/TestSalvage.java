import com.grayawa.zipff.Salvage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** 本地验证用：对真实坏包跑一遍抢救逻辑，和 zip -FF 的结果对比。 */
public final class TestSalvage {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: TestSalvage <zip> <输出目录>");
            System.exit(2);
        }
        File in = new File(args[0]);
        File outDir = new File(args[1]);
        outDir.mkdirs();

        Salvage.Sink sink = new Salvage.Sink() {
            @Override
            public OutputStream open(String path) throws IOException {
                File f = new File(outDir, path);
                File p = f.getParentFile();
                if (p != null) {
                    p.mkdirs();
                }
                return new FileOutputStream(f);
            }

            @Override
            public void delete(String path) {
                new File(outDir, path).delete();
            }
        };

        Salvage.Listener listener = new Salvage.Listener() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void onEntryDone(String path, long bytes, boolean ok, String reason) {
                System.out.println((ok ? "  OK   " : "  BAD  ") + path + "  " + bytes + "B"
                        + (reason == null ? "" : "  <- " + reason));
            }

            @Override
            public void onNotice(String message) {
                System.out.println("  ..   " + message);
            }
        };

        long t0 = System.currentTimeMillis();
        Salvage.Result r;
        try (FileInputStream fis = new FileInputStream(in)) {
            r = new Salvage(sink, listener).run(fis);
        }
        System.out.println(in.getName() + " -> " + r.summary()
                + "  用时 " + (System.currentTimeMillis() - t0) + "ms");
        if (!r.foundAnything()) {
            System.out.println("  警告: 没有识别到任何 zip 条目");
        }
    }
}
