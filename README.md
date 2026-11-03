# zip-FF-Tool

一个安卓 App：**批量选择 zip 文件 → 按 `zip -FF` 的方式抢救坏包 → 解压到指定文件夹**。

安卓系统里没有 `zip` 命令，所以抢救逻辑用 Java 重写（`Salvage.java`），行为和
Info-ZIP 的 `zip -FF`（"salvage what can"）一致：

- 不读可能损坏的中央目录，直接在字节流里顺序寻找本地文件头 `PK\3\4`；
- 逐个解出条目，用 CRC32 校验；坏条目直接丢弃，**从当前位置继续向后找下一个头**，
  所以损坏位置之后的好文件照样能救出来；
- 支持 deflate / stored、数据描述符（流式写入的 zip）、ZIP64 长度字段；
- 文件名先按 UTF-8 解码，失败退回 GBK，再退回 Latin-1（中文包不会乱码）；
- 拒绝 `../`、绝对路径这类越界条目。

## 用法

1. 装 APK，打开 App；
2. ① 选择 ZIP 文件（可多选，系统文件选择器里长按可多选）；
3. ② 选择输出文件夹（SAF 目录授权，不需要存储权限）；
4. ③ 开始修复并解压。

每个 zip 解压到 `输出文件夹/<原文件名去掉 .zip>/`，目录结构保持原样。
日志区会逐条列出救回 / 损坏的条目，最后给出汇总。

也可以从别的 App「用其他应用打开」或「分享」一个 zip 给本 App。

## 构建

推送到 `main` 分支即触发 GitHub Actions（`.github/workflows/build.yml`）：

1. `engine-test`：用 Python 造好包（截断、头部损坏、中间损坏、数据描述符、垃圾文件），
   编译引擎跑一遍并校验内容逐字节一致；
2. `build`：Gradle 打 debug APK，产物在 Actions 的 Artifacts 里
   （`zip-FF-Tool-<版本>-<commit>.apk`）。

本地构建：

```sh
gradle assembleDebug
# 产物 app/build/outputs/apk/debug/app-debug.apk
```

引擎自检（只需要 JDK + python3，不需要 Android SDK）：

```sh
sh tools/selftest.sh
```

## 说明与限制

- 本工具**不产出中间修复包**，按 `-FF` 的抢救规则直接把能救的条目写进目标文件夹；
- 加密（带密码）条目无法解出，会记为「跳过」；
- 条目数以千计的包，SAF 逐个建文件会慢一些（每个文件一次系统调用）；
- 损坏条目里已经写了一半的数据会被删掉，避免留下看起来正常的坏文件；
- 抢救能力有上限：压缩流本身被破坏、或者连文件头都认不出来的包救不回来。

## 目录

```
app/src/main/java/com/grayawa/zipff/
  Salvage.java       抢救引擎（可独立编译测试，不依赖 Android）
  SafTreeSink.java   把条目写进 SAF 目录树
  MainActivity.java  界面与任务调度
tools/TestSalvage.java  本地跑引擎的命令行壳
tools/selftest.sh       造坏包 + 校验的自检脚本
```

MIT License.
