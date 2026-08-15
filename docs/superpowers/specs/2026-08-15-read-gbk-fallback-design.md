# Read 工具：非 UTF-8 文件 GBK 自动降级（2026-08-15）

## 1. 背景（用户报告）

Read 工具读 `D:\temp\a.txt`（Windows 绝对路径）失败，报错
「Input length = 1」；改用 Bash `cat` 成功读取。该命令原本用于测试
越界读确认弹框，实际暴露编码缺陷。

## 2. 根因（systematic-debugging 证据链）

1. **错误来源**：全仓与依赖 jar 均无 "Input length" 字面量；grep
   `E:/javame/jdk8/src.zip` 定位到 `java.nio.charset.MalformedInputException`
   与 `UnmappableCharacterException` 的 `getMessage()` 均为
   `"Input length = " + inputLength`。
2. **触发点**：[ReadTool.execute:71](../src/main/java/com/minion/core/tools/ReadTool.java#L71)
   `Files.readAllLines(p, StandardCharsets.UTF_8)`——InputStreamReader 的
   decoder 默认 REPORT 模式，遇非法 UTF-8 序列抛 MalformedInputException。
3. **文件编码实锤**：`od -c /d/temp/a.txt` → `\xB0\xA2\xCA\xAB\xB5\xA4\xB6\xD9`，
   8 字节 = 4 个双字节汉字，Bash `cat` 显示为「阿诗丹顿」——GBK 编码
   （Windows 记事本默认 ANSI 保存的典型产物），非 UTF-8，`file` 判定
   ISO-8859 text。
4. **对照**：Bash `cat` 字节透传、不校验编码 → 成功。文件本身可读，
   与权限/路径无关，Read 与 Bash 的唯一差异是编码解码。

## 3. 设计决策

### 3.1 编码降级策略（用户拍板：GBK 自动降级）

`ReadTool` 读取流程：UTF-8 严格解码 → 失败则 GBK 兜底 → 仍失败给可读错误。

- **UTF-8 优先**：正常路径零打扰——UTF-8 文件输出与现行为完全一致，
  不加任何标注。
- **GBK 兜底**：捕获 `CharacterCodingException`（Malformed/Unmappable 两子类）
  后改用 `Charset.forName("GBK")` 重读。GBK 对任意字节宽容、几乎不失败，
  Windows 下 GBK 文本（记事本 ANSI 保存）可直接读出，不再绕道 Bash。
- **仍失败**（理论上极少）：返回可读错误
  「文件解码失败（UTF-8 与 GBK 均失败，疑似二进制或未知编码）」。

### 3.2 转码标注

GBK 降级成功时，输出内容首行标注 `[GBK 编码文件，已自动转码显示]`：

- 让用户/模型感知内容经过转码（避免把乱码当真、或误以为编码是 UTF-8）。
- 不占 offset/limit 计数，也不带行号；翻页时重复显示（仅一行，可接受）。

### 3.3 已知边界

- **二进制文件**：GBK 解码宽容，二进制文件（如 PNG 头）会以乱码形式读出。
  用户已明确选择不加二进制探测（方案 A），后续需要可加 NUL 探测。
- **EditTool / GrepTool 同类风险**（用户确认后本次一并修复）：新增
  `TextFiles` 编码辅助统一降级逻辑；EditTool 按原编码写回（不破坏文件其余
  内容），GrepTool 静默降级（只读安全）。

## 4. 实现

| 文件 | 改动 |
|---|---|
| core/tools/TextFiles.java | 新增编码辅助：`readAllLines(Path)` 与 `decode(byte[])`（UTF-8 严格 → GBK 兜底，带降级标志/实际编码返回） |
| core/tools/ReadTool.java | 改用 TextFiles.readAllLines，降级时输出首行标注；删除内联 try/catch 与 GBK 常量 |
| core/tools/GrepTool.java | visitFile 改用 TextFiles.readAllLines（GBK 文件不再静默跳过） |
| core/tools/EditTool.java | 改用 TextFiles.decode 读 + `getBytes(实际编码)` 写回（GBK 文件编辑成功且不重编码破坏） |
| test FileToolsTest | 新增 GBK 文件自动转码用例（断言内容正确+含标注）、UTF-8 文件无标注用例（零打扰）、GBK 文件 grep 命中用例 |
| test EditToolsTest | 新增 GBK 文件编辑用例（断言写回仍为 GBK 编码） |

## 5. 验证

- TDD：先写失败测试确认 RED——`read_gbkFile_autoDecoded` 实抛
  `MalformedInputException: Input length = 1`（与线上完全一致）；
  `edit_gbkFile_editsAndPreservesEncoding` 报「未找到待替换内容」、
  `grep_gbkFile_matches` 报「未匹配」。实施后全部转 GREEN。
- 全量 `mvn test` 通过：438 个用例 0 失败。
- 手动验证（可选）：真实 GUI 中让模型读/编辑/搜索 GBK 文件，确认可读可改可搜。

## 6. 文档同步

- ARCHITECTURE.md：ReadTool 职责追加「非 UTF-8 文件自动 GBK 降级转码」。
