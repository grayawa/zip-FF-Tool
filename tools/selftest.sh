#!/bin/sh
# 引擎自检：造几个真实的坏包，跑一遍抢救逻辑，校验救回来的内容和原文件逐字节一致。
# 用法: sh tools/selftest.sh
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT INT TERM
SRC="$WORK/src"
ZIPS="$WORK/zips"
OUT="$WORK/out"
mkdir -p "$SRC/sub" "$ZIPS" "$OUT"

echo "== 1. 生成测试数据 =="
python3 - "$SRC" "$ZIPS" <<'PY'
import io, os, sys, zipfile, random
src, zips = sys.argv[1], sys.argv[2]
random.seed(7)
for i in range(1, 4):
    open(os.path.join(src, f'f{i}.bin'), 'wb').write(random.randbytes(60000))
open(os.path.join(src, 'sub', '中文名.txt'), 'w', encoding='utf-8').write('文件名不是 ASCII 也没关系\n' * 100)

good = os.path.join(zips, '01-完好.zip')
with zipfile.ZipFile(good, 'w', zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(src, 'sub', '中文名.txt'), '文档/中文名.txt')
    for i in range(1, 4):
        z.write(os.path.join(src, f'f{i}.bin'), f'data/f{i}.bin')

raw = open(good, 'rb').read()
open(os.path.join(zips, '02-尾部截断.zip'), 'wb').write(raw[:len(raw) - len(raw) // 3])
open(os.path.join(zips, '03-头部损坏.zip'), 'wb').write(raw[:len(raw)//6] + bytes(len(raw)//2) + raw[len(raw)//6:])

b = bytearray(raw); mid = len(b) // 2
for k in range(mid, mid + 1500):
    b[k] ^= 0xFF
open(os.path.join(zips, '04-中间损坏.zip'), 'wb').write(bytes(b))

class Unseekable(io.RawIOBase):   # 强制写数据描述符
    def __init__(self, f): self.f = f
    def writable(self): return True
    def seekable(self): return False
    def write(self, b): return self.f.write(b)

with open(os.path.join(zips, '05-数据描述符.zip'), 'wb') as f:
    with zipfile.ZipFile(Unseekable(f), 'w', zipfile.ZIP_DEFLATED) as z:
        for i in range(1, 4):
            z.writestr(f'd{i}.bin', random.randbytes(40000))

open(os.path.join(zips, '06-不是zip.zip'), 'wb').write(bytes(random.randbytes(300000)))
print('  已生成', len(os.listdir(zips)), '个测试包')
PY

echo "== 2. 编译引擎 =="
mkdir -p "$WORK/classes"
javac -encoding UTF-8 -d "$WORK/classes" \
  "$ROOT"/app/src/main/java/com/grayawa/zipff/Salvage.java \
  "$ROOT"/app/src/main/java/com/grayawa/zipff/ZipCryptoSupport.java \
  "$ROOT/tools/TestSalvage.java"
echo "  编译通过"

echo "== 3. 逐个抢救 =="
for f in "$ZIPS"/*.zip; do
  name=$(basename "$f" .zip)
  rm -rf "$OUT/$name"
  java -cp "$WORK/classes" TestSalvage "$f" "$OUT/$name" 2>&1 | tail -6
done

echo "== 4. 加密包（有密码就解密） =="
PASS=selftest123
if command -v 7z >/dev/null 2>&1; then
  (cd "$SRC" && 7z a -tzip -p"$PASS" -mem=AES256 "$ZIPS/07-AES256.zip" f1.bin sub >/dev/null 2>&1) \
    && echo "  造 AES-256 包成功"
else
  echo "  跳过 AES（没有 7z）"
fi
if command -v zip >/dev/null 2>&1; then
  (cd "$SRC" && zip -q -P "$PASS" "$ZIPS/08-ZipCrypto.zip" f1.bin sub/中文名.txt) \
    && echo "  造 ZipCrypto 包成功"
fi

for f in "$ZIPS"/07-AES256.zip "$ZIPS"/08-ZipCrypto.zip; do
  [ -f "$f" ] || continue
  name=$(basename "$f" .zip)
  echo "--- $name 正确密码"
  rm -rf "$OUT/$name"
  java -cp "$WORK/classes" TestSalvage "$f" "$OUT/$name" "$PASS" 2>&1 | tail -3
  echo "--- $name 错误密码"
  rm -rf "$OUT/$name-bad"
  java -cp "$WORK/classes" TestSalvage "$f" "$OUT/$name-bad" wrong-password 2>&1 | tail -2
done

echo "--- 加密包解出的内容必须与原文件一致"
for case in 07-AES256 08-ZipCrypto; do
  [ -d "$OUT/$case" ] || { echo "  $case 没有输出目录"; exit 1; }
  cmp -s "$OUT/$case/f1.bin" "$SRC/f1.bin" \
    && echo "  $case: f1.bin 一致" || { echo "  $case: f1.bin 不一致"; exit 1; }
  cmp -s "$OUT/$case/sub/中文名.txt" "$SRC/sub/中文名.txt" \
    && echo "  $case: 中文名.txt 一致" || { echo "  $case: 中文名.txt 不一致"; exit 1; }
  if [ -n "$(ls -A "$OUT/$case-bad" 2>/dev/null)" ]; then
    echo "  $case: 错误密码竟然写出了文件"; exit 1
  fi
done

echo "== 5. 校验内容 =="
python3 - "$SRC" "$OUT" <<'PY'
import os, sys
src, out = sys.argv[1], sys.argv[2]
expect = [
    ('01-完好', {'文档/中文名.txt': 'sub/中文名.txt', 'data/f1.bin': 'f1.bin',
                 'data/f2.bin': 'f2.bin', 'data/f3.bin': 'f3.bin'}),
    ('02-尾部截断', {'文档/中文名.txt': 'sub/中文名.txt', 'data/f1.bin': 'f1.bin',
                     'data/f2.bin': 'f2.bin'}),
    ('03-头部损坏', {'data/f2.bin': 'f2.bin', 'data/f3.bin': 'f3.bin'}),
    ('04-中间损坏', {'data/f1.bin': 'f1.bin', 'data/f3.bin': 'f3.bin'}),
]
fail = 0
for case, mapping in expect:
    for rel, orig in mapping.items():
        p = os.path.join(out, case, rel)
        if not os.path.exists(p):
            print(f'  缺失 {case}/{rel}'); fail += 1; continue
        a = open(p, 'rb').read(); b = open(os.path.join(src, orig), 'rb').read()
        if a != b:
            print(f'  内容不一致 {case}/{rel}'); fail += 1
junk_dir = os.path.join(out, '06-不是zip')
junk_files = len(os.listdir(junk_dir)) if os.path.isdir(junk_dir) else 0
if os.path.exists(os.path.join(out, '04-中间损坏/data/f2.bin')):
    print('  损坏条目被误留'); fail += 1
if junk_files:
    print(f'  垃圾文件竟产生了 {junk_files} 个条目'); fail += 1
if fail:
    print(f'失败: {fail} 项'); sys.exit(1)
print('全部通过')
PY
