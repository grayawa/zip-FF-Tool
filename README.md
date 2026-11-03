# zip-FF-Tool

一个安卓 App：**批量选择文件 → 按 `zip -FF` 的方式抢救 → 解压到指定文件夹**。
界面用 [MiuiX](https://github.com/compose-miuix-ui/miuix)（Compose Multiplatform 的 MIUI/HyperOS 风格组件库）实现。

安卓系统里没有 `zip` 命令，所以抢救逻辑用 Java 重写（`Salvage.java`），行为和
Info-ZIP 的 `zip -FF`（"salvage what can"）一致：

- 不读可能损坏的中央目录，直接在字节流里顺序寻找本地文件头 `PK\3\4`；
- 逐个解出条目并做 CRC32 校验；坏条目丢弃，**从当前位置继续向后找下一个头**，
  所以损坏位置之后的好文件照样能救出来；
- 支持 deflate / stored、数据描述符（流式写入的 zip）、ZIP64 长度字段；
- 文件名先按 UTF-8 解码，失败退回 GBK，再退回 Latin-1（中文包不乱码）；
- 拒绝 `../`、绝对路径这类越界条目。

## 用法

1. 装 APK，打开 App；
2. ① 选择文件（可多选）；
3. ② 选择输出文件夹（SAF 目录授权，不需要存储权限）；
4. （可选）在密码框里填上解压密码——空着就只报告「需要密码」，填了会尝试解密；
5. ③ 开始修复并解压。

**输入不要求是 `.zip`**：只认文件内容（是不是 zip 结构），不看扩展名。
`zip` / `apk` / `jar` / `docx` / `cbz` / 分卷 `.zip.001` / 没有扩展名的数据包都可以直接选，
选择器也不再按 MIME 过滤。

每个文件解压到 `输出文件夹/<去掉分卷号和扩展名的文件名>/`，目录结构保持原样；
日志区逐条列出救回 / 损坏的条目，最后给出汇总。

- 上次选的文件和输出目录会被记住，下次打开直接点 ③ 即可；
- 输出目录的选择器会直接打开上次的目录；
- 也可以从别的 App「用其他应用打开」或「分享」一个文件给本 App。

## 构建

推送到 `main` 即触发 GitHub Actions（`.github/workflows/build.yml`）：

1. `engine-test`：用 Python 造好包（截断、头部损坏、中间损坏、数据描述符、垃圾文件），
   编译引擎跑一遍并校验内容逐字节一致；
2. `build`：Gradle 打包 → 校验签名指纹 → 上传 Artifacts → 发布到滚动 Release：

```
https://github.com/grayawa/zip-FF-Tool/releases/download/latest/zip-FF-Tool-latest.apk
```

### release 签名

CI 里配了这四个 Secrets 就打 **release 包**（否则退回 debug 包）：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | keystore 的 base64（`base64 -w0 zipff-release.jks`） |
| `KEYSTORE_PASSWORD` | keystore 口令 |
| `KEY_ALIAS` | 别名（本项目是 `zipff`） |
| `KEY_PASSWORD` | key 口令 |

`app/build.gradle` 只在检测到 `KEYSTORE_BASE64` 时才注册 `signingConfigs.release` 并让
release 用它签名，所以本地没有这些环境变量也能正常 `assembleDebug`。

工具链：AGP 9.5.0 / Gradle 9.8.2 / Kotlin 2.4.20 / compileSdk 37 / minSdk 26。
（MiuiX 0.9.4 要求 `compileSdk 37`；AGP 9 自带 Kotlin 支持，只需额外应用 Compose 编译器插件。）

本地构建：

```sh
gradle assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
KEYSTORE_BASE64=... KEYSTORE_PASSWORD=... KEY_ALIAS=zipff KEY_PASSWORD=... \
  gradle assembleRelease  # 需要先放好 build/keystore.jks
```

引擎自检（只需要 JDK + python3，不需要 Android SDK）：

```sh
sh tools/selftest.sh
```

## 说明与限制

- 本工具**不产出中间修复包**，按 `-FF` 的抢救规则直接把能救的条目写进目标文件夹；
- **支持解密**：传统 ZipCrypto 和 WinZip AES（method 99，AES-128/192/256）都能解；
  密码框留空时只逐条报告「需要密码」，填错密码会明确报「密码错误」，不会写出半截文件；
  （`zip -FF` 遇到加密包只会把密文原样复制进修复包，产物仍然打不开，本工具不产生这种无效产物）
- AES 用 PBKDF2-HMAC-SHA1(pw, salt, 1000) 派生密钥，AES-CTR 解密，并用 10 字节 HMAC-SHA1 校验完整性；
- 密码只在内存里用，不落盘、不写偏好；
- 条目数以千计的包，SAF 逐个建文件会慢一些（每个文件一次系统调用）；
- CRC 不符的条目会被删掉，避免留下看起来正常的坏文件；
- 抢救能力有上限：压缩流本身被破坏、或者连文件头都认不出来的包救不回来。

## 目录

```
app/src/main/java/com/grayawa/zipff/
  Salvage.java       抢救引擎（纯 Java，可脱离 Android 编译测试）
  SafTreeSink.java   把条目写进 SAF 目录树
  MainActivity.kt    MiuiX / Compose 界面与任务调度
tools/TestSalvage.java  本地跑引擎的命令行壳
tools/selftest.sh       造坏包 + 校验的自检脚本
```

MIT License.
