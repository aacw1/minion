# Shell 工具中文乱码修复：bash locale/脚本编码探测（Win7 老 msys 默认 ANSI）

日期：2026-09-15
状态：已确认（评审意见：探测方案照做，**去掉 `-Dminion.shell.locale` 逃生阀**，保持最小面积）

## 背景与问题

现象（用户报告）：Windows 7 上 Bash 工具执行含中文的命令乱码，麒麟（Linux）正常。

```
{"command":"ls \"D:/test/普惠授信/公式/\"}                    → 乱码
{"command":"Lang=zh_CN.UTF-8 ls \"D:/test/普惠授信/公式/\"}   → 正常
```

用户初判「输出是 UTF-8，但命令行是 GBK，要对上」——方向正确，需精确化：**是命令脚本文件的
编码（我们恒写 UTF-8）与 bash 进程实际使用的 locale charset（Win7 老 msys 默认 = 系统 ANSI
代码页 GBK）不一致**。

### 根因（已在本机受控复现）

`BashTool` 的执行链路：

1. `probe(command, pidFile)` 拼出脚本内容 → `Files.write(scriptFile, ...getBytes(UTF_8))`
   （BashTool.java:92）——**恒定 UTF-8**；
2. `buildShellCommand` 走 `bash <scriptFile>`（Windows 优先 Git Bash）；
3. msys/cygwin 程序（`ls` 等）把 argv 的**原始字节**按 `LC_CTYPE` 的 charset 转 UTF-16 再调
   Win32 API；输出时按同一 charset 转回字节；
4. `detectCharset` 探测 stdout 字节：UTF-8 严格解码失败则回退 GBK。

当 bash 的 charset 不是 UTF-8（未设 `LANG`/`LC_ALL` 且 msys 版本较老 → 取 ANSI 代码页 GBK）：

- 脚本里的 UTF-8 中文路径被按 GBK 解释 → 转出的 UTF-16 是错字 → `ls` 报
  `No such file or directory`，**命令直接失败**；
- 错误消息回显的路径字节与我们的解码假设错位 → 模型看到 mojibake。

本机（Win10 + msys 3.3.6，`locale` 默认即 `C.UTF-8`，故用户说"没有问题"）用 Java/JDK8 复刻
BashTool 链路做受控实验，注入 `LC_ALL/LANG=zh_CN.GBK` 模拟 Win7 条件：

| 脚本编码 | bash locale | exit | 模型看到的输出 |
|---|---|---|---|
| UTF-8 | 未注入（msys 默认 C.UTF-8） | 0 | `中文文件.txt` ✅ |
| UTF-8 | zh_CN.GBK（模拟 Win7） | **2** | `ls: cannot access '.../缂栫爜瀹為獙/瀛愮洰褰'$'\225'` ❌ 典型 UTF-8-as-GBK 乱码 |
| UTF-8 | 注入 `LC_ALL=C.UTF-8` | 0 | `中文文件.txt` ✅ |
| **GBK** | zh_CN.GBK | 0 | 输出 GBK 字节 → `detectCharset` 判 GBK → `中文文件.txt` ✅ |

结论：**只要「脚本编码 + 输出解码」与 bash 实际 charset 对上，两条路都能修好**；对不上必然
乱码。与 `os.version` 无因果关系（Win10 装老 Git 同样会乱码，Win7 装新 Git 则不会）——
因此**不建议按 Win7 做特判**。

补充证据：用户用 `Lang=`（大写 L）也生效，说明 msys 从 Windows 环境块读变量大小写不敏感，
即"注入 locale 环境变量"这条路在用户的 Win7 现场已被证明可行。

## 目标

1. Win7（及任何 bash charset ≠ UTF-8 的环境）下，含中文的命令能正确执行、输出不乱码。
2. 现状正常的环境（Win10 新 msys、麒麟/Linux UTF-8）**行为零变化**，不新增可感知开销。
3. 不依赖 OS 版本猜测，不依赖某台机器的手工环境变量；探测失败时**回退现状**，绝不劣化。
4. 保留既有能力：`cmd /c` 分支、原生 Windows 程序的 GBK 输出（`detectCharset`）、超时组杀、
   输出截断落盘。

## 非目标

- 不改 `detectCharset` 的分片探测策略（见「已知局限」）。
- 不处理 BashTool 之外的进程派生点（`ChromeLauncher`、MCP stdio、`Boot` 重启）：这些不经
  bash 脚本传递中文命令，本次症状无关；如后续出现同类问题另立专题。
- 不引入 OS 版本判定（`os.version == 6.1`）分支。
- 不改 GUI/配置项，不新增 config.properties 键，不加系统属性开关（评审决定：探测自适应，异常回退现状，无需逃生阀）。

## 方案：ShellLocale 一次性探测（进程级缓存）

### 1. 新增 `com.minion.core.tools.ShellLocale`

不可变值对象 + 静态缓存，字段：

```java
public final Map<String, String> extraEnv;   // 需注入子进程的 locale 变量（可为空）
public final Charset scriptCharset;          // 写命令脚本用的编码
public final Charset outputCharset;          // 期望的 stdout 编码（仅用于探针判定与诊断）
```

候选表（按序探测，第一个通过者选定）：

| # | extraEnv | scriptCharset | outputCharset | 命中场景 |
|---|---|---|---|---|
| 1 | 空 | UTF-8 | UTF-8 | Win10/新 msys、麒麟（默认即 UTF-8，零变化） |
| 2 | `LC_ALL=C.UTF-8`、`LANG=C.UTF-8` | UTF-8 | UTF-8 | 老 msys 支持 C.UTF-8 |
| 3 | `LC_ALL=zh_CN.UTF-8`、`LANG=zh_CN.UTF-8` | UTF-8 | UTF-8 | 只认 xx_YY.UTF-8 的 msys（用户现场已验证此类可行） |
| 4 | 空（兜底） | Windows=GBK / 其他=UTF-8 | 同 scriptCharset | msys 完全不支持 UTF-8 locale（如 MSYS 1.0） |

### 2. 探针实现

- 探针目录：`java.io.tmpdir` 下建 `minion-enc-probe<rand>/中文探针-<rand>/中文文件.txt`
  （中文目录 + 中文文件名，覆盖 argv→UTF-16 与输出→字节两次转换）。
- 探针脚本：按候选 `scriptCharset` 写 `ls "<中文目录>"`，用 `ProcessBuilder(bash, scriptFile)`
  **直接派生**（不复用 `BashTool.execute`，避免递归触发探测），`redirectErrorStream(true)`，
  `waitFor(10s)`，读原始字节。
- 判定：`exit == 0 && new String(bytes, outputCharset)` 含预期中文文件名 → 该候选通过。
- 清理：探针目录与脚本文件用后删除（`deleteOnExit` 兜底）。
- 失败/异常/超时：跳到下一候选；全部异常 → 返回候选 1（等价现状）。
- 缓存：`static volatile ShellLocale` + `synchronized` 双检；多会话共享，仅首次 Bash 调用付
  一次探测成本（Win10 上候选 1 即通过，约 50–150ms）。

### 3. 探测适用范围

- 仅 bash 路径需要探测：Windows 找到 Git Bash → 用 `bash.exe` 探；Unix → 用 `/bin/sh` 探。
- `cmd /c command` 分支（无 Git Bash）**不注入、不探测**：命令由 JDK 以宽字符 API 传递，
  输出 GBK 由 `detectCharset` 处理。
- `killTree` 的 `bash -c "kill -9 -<pid>"`、`regQuery` 的 `reg query`：纯 ASCII 命令，不注入。

### 4. `BashTool` 改动（4 处，均为小改）

```java
ShellLocale sl = ShellLocale.get(shellExeOrNull);                    // ① 取探测结果（缓存）
Files.write(scriptFile.toPath(), probe(command, pidFile).getBytes(sl.scriptCharset)); // ② 脚本编码
ProcessBuilder pb = new ProcessBuilder(cmd);
if (!sl.extraEnv.isEmpty()) pb.environment().putAll(sl.extraEnv);     // ③ 注入 locale
Charset cs = detectCharset(bin);                                      // ④ 输出解码：保持不变
```

`buildShellCommand` 需要把「实际使用的 shell 可执行文件路径」暴露给调用方（供探针复用同一
bash），改为返回一个小结构或在 `execute` 内先解析 bash 路径再构造命令行——取后者，改动更小。

## 详细流程

### A. 触发与缓存

```
BashTool.execute(args)
  ├─ 纯 cd 命令 → 直接改 cwd 返回（不涉及 shell，不触发探测）
  ├─ 解析 shell：Windows → findGitBash()；找到 = bash 路径，找不到 = null（cmd /c 分支）
  │              非 Windows → /bin/sh
  ├─ shell != null 时：ShellLocale sl = ShellLocale.get(shell)   ← 懒探测，进程级缓存
  │     └─ cached != null ? 直接返回 : synchronized 双检 → probe() → 写 cached
  │        （多会话/子代理共享同一份结果；仅首次 Bash 调用付探测成本）
  └─ shell == null（cmd /c）：不探测、不注入，sl = ShellLocale.PLAIN（等价现状）
```

### B. 单个候选的探针步骤（`probeOne`）

1. 建探针目录：`java.io.tmpdir/minion-enc-probe<n>/中文探针<n>/中文文件.txt`
   （目录名 + 文件名都含中文：一次覆盖「argv 字节→UTF-16」与「UTF-16→stdout 字节」两次转换）；
2. 按候选 `scriptCharset` 写探针脚本：`ls "<中文目录绝对路径>"`（路径分隔符统一 `/`）；
3. `new ProcessBuilder(shell, scriptFile)` + `redirectErrorStream(true)` +
   `pb.environment().putAll(候选 extraEnv)`，工作目录 = 探针父目录；
4. `waitFor(10, SECONDS)`；超时 → `destroyForcibly()` 并判失败；
5. 读全部 stdout 字节；
6. **判定通过**：`exitValue == 0` **且** `new String(bytes, outputCharset)` 含 `中文文件.txt`；
7. 无论成败：删脚本文件；整轮探测结束后递归删探针目录（`deleteOnExit` 兜底）。

> 不复用 `BashTool.execute` 跑探针：它会再次调 `ShellLocale.get` → 递归；也会触发确认闸门、
> 截断落盘、pid 探针等无关逻辑。探针是独立的极简派生。

### C. 候选顺序与决策表

| 顺序 | extraEnv | scriptCharset | 判定用 outputCharset | 通过即选定 |
|---|---|---|---|---|
| 1 | 空 | UTF-8 | UTF-8 | ✅ 现状正常环境（Win10 新 msys、麒麟）到此结束 |
| 2 | `LC_ALL=C.UTF-8`、`LANG=C.UTF-8` | UTF-8 | UTF-8 | ✅ 老 msys 认 C.UTF-8 |
| 3 | `LC_ALL=zh_CN.UTF-8`、`LANG=zh_CN.UTF-8` | UTF-8 | UTF-8 | ✅ 只认 xx_YY.UTF-8（用户 Win7 现场已证可行） |
| 4 | 空 | Windows=GBK / 其他=UTF-8 | 同 scriptCharset | ✅ msys 完全不支持 UTF-8 locale（本机已实测此路可用） |

- 4 个候选全不通过，或探测过程抛任何异常 → 返回候选 1（**等价现状，绝不劣化**）；
- 候选 4 在 Windows 上等价「脚本按 GBK 写、输出按 GBK 解」，`detectCharset` 天然兜住。

### D. 探测结果的使用（BashTool 内 3 个落点）

```java
// ① 脚本编码：原来恒 UTF-8
Files.write(scriptFile.toPath(), probe(command, pidFile).getBytes(sl.scriptCharset));
// ② locale 注入：原来不动子进程环境
if (!sl.extraEnv.isEmpty()) pb.environment().putAll(sl.extraEnv);
// ③ 输出解码：保持 detectCharset 不变（原生 Windows 程序仍可能输出 GBK）
Charset cs = detectCharset(bin);
```

### E. 两条现场的完整时序

**Win7（老 msys，默认 GBK）**
```
首次 Bash 调用 → 候选1 探针：脚本 UTF-8 + 无注入 → ls exit=2 → 失败
              → 候选2 探针：脚本 UTF-8 + LC_ALL=C.UTF-8 → exit=0 且输出含中文名 → 选定
              → 缓存 {LC_ALL=C.UTF-8, LANG=C.UTF-8, UTF-8}
后续调用 → 直接命中缓存（0 额外开销）
用户命令 ls "D:/test/普惠授信/公式/" → 脚本 UTF-8 + bash charset UTF-8 → 对上 → 正常输出
```

**Win10 / 麒麟（默认已 UTF-8）**
```
首次 Bash 调用 → 候选1 探针：exit=0 且输出含中文名 → 立即选定（约 50–150ms，一次性）
              → 缓存 {空 env, UTF-8} → 后续行为与今天完全一致（零变化）
```

### F. 可测性

`ShellLocale` 的「跑一次探针」抽为包内可注入接口：

```java
interface ProbeRunner { ProbeResult run(String shell, Map<String,String> env,
                                        Charset scriptCharset, Path cnDir, String expectName); }
```

单测用假 `ProbeRunner` 编排各候选的通过/失败组合，验证选择逻辑与异常回退；真实 bash 的
端到端验证放 `BashToolTest`（无 bash 环境自动跳过）。静态缓存提供包内 `resetForTest()`。

## 测试计划

1. **`ShellLocaleTest`（新增，纯单测）**：把「跑一次探针」抽为可注入的函数式接口
   （`ProbeRunner`：入参 env+scriptCharset，出参 exit/输出字节），用假实现验证选择逻辑：
   - 候选 1 通过 → `extraEnv` 空、`scriptCharset=UTF-8`；
   - 候选 1 失败、2 通过 → 注入 `LC_ALL/LANG=C.UTF-8`；
   - 1–3 全失败、GBK 探针通过 → `scriptCharset=GBK`、`extraEnv` 空；
   - 探针抛异常/超时 → 回退候选 1（不抛异常给上层）；
2. **`BashToolTest`（回归 + 新增）**：
   - 既有 `execute_utf8Output_decoded`、`execute_gbkOutput_decoded` 必须继续通过（防注入
     locale 后原生 GBK 输出解码回归）；
   - 新增 `execute_chinesePath_ls`：在工作区建中文目录 + 中文文件名，`ls` 后断言输出含正确
     中文且 `r.ok == true`（本机 Git Bash 可用时执行，无 bash 时跳过）；
   - 新增「注入 env 不影响超时组杀」回归：`sleep 30 &` + 注入 locale 后仍按时超时、无残留进程。
3. **手工验收**：Win7 现场执行 `ls "D:/test/普惠授信/公式/"` 与 `cat` 中文文件；麒麟回归一次。
4. `mvn compile` + `mvn test` 全绿。

## 影响面与风险

- 影响范围：仅 `BashTool` 命令执行路径 + 新增一个类；不改工具协议、不改 GUI。
- 首次 Bash 调用多一次探针派生（候选 1 通常即通过）；探针失败最多 4 次派生（每次 ≤10s，
  实际毫秒级），仅发生在本就乱码的环境。
- 注入 `LC_ALL/LANG` 会改变**所有**经 Bash 工具运行的命令的 locale：
  - 好处：msys 工具（ls/grep/sed/git）统一 UTF-8，跨机器一致；
  - 风险：极少数依赖 `LANG` 决定输出语言的程序（如 GNU `--help` 文本、`git` 提示语）会变成
    C/英文或中文——不影响正确性，只影响文案；`C.UTF-8` 优先级高于 `zh_CN.UTF-8` 已把该
    影响降到最低。
- 回退：探测异常或全部候选不通过 → 候选 1（等价现状，不劣化）。

## 已知局限（本次不修，记录备查）

`detectCharset` 只探测**首次 `read` 返回的字节**（≤8KB）：若首片纯 ASCII、后续片段才出现
GBK 中文（混合输出或分片到达），会误判为 UTF-8 导致后半段乱码。该缺陷在 Win10 上同样存在，
与本次根因无关；可选加固（后续专题）：探测读满 8KB 或 EOF、或以 `ShellLocale.outputCharset`
作先验、或解码中途遇 malformed 时重解。

## 文档同步

- `docs/ARCHITECTURE.md`：`core/tools` 下补 `ShellLocale` 一行说明。
- `README.md`：故障排查段补「Bash 工具中文乱码：自动探测 bash 编码」条目。
- 实施后在本文档追加「实施记录」（含 Win7 现场验证结果）。

## 实施记录（2026-09-15）

### 改动清单

| 文件 | 改动 |
|---|---|
| `core/tools/ShellLocale.java`（新增） | 探测结果值对象 + 4 候选表 + 真实探针（`ls 中文目录`）+ 进程级缓存（volatile + synchronized 双检）+ `ProbeRunner` 可注入接缝 |
| `core/tools/BashTool.java` | ①新增 3 参构造（`ShellLocale` 构造注入，2 参构造委托 null=探测）；②`resolveShellExe()` 先解析 shell，`buildShellCommand` 改为接收已解析 shell（cmd /c 分支 shell=null）；③脚本按 `locale.scriptCharset` 写；④`pb.environment().putAll(locale.extraEnv)`（非空时）。输出解码 `detectCharset` 未改 |
| `test/.../ShellLocaleTest.java`（新增） | 8 例：候选1 命中不注入 / 注入 C.UTF-8 / 注入 zh_CN.UTF-8 / GBK 兜底 / exit==0 但输出不匹配全否 / 探针抛异常回退 / get 缓存只探一次 / PLAIN 常量 |
| `test/.../FakeShellProbe.java`（新增） | 假探针（按 locale+脚本编码决定成败），两个测试类共用 |
| `test/.../BashToolTest.java` | 3 例：中文路径 `ls` 端到端 / locale 注入真到达子进程 / 脚本编码确实生效（GBK 脚本喂 UTF-8 bash 必失败，反向钉住链路） |
| `docs/ARCHITECTURE.md`、`README.md` | 补 `ShellLocale` 说明与「Bash 命令的中文编码（自动探测）」排查段 |

按评审意见**未实现** `-Dminion.shell.locale` 逃生阀。

### 验证证据

1. 单测：`ShellLocaleTest` 8/8、`BashToolTest` 30/30、全量 `mvn test` **1178/1178 绿**（0 失败 0 错误 0 跳过）。
2. 探测结果（本机 Win10 + msys 3.3.6）：
   - 正常环境：`extraEnv={} scriptCharset=UTF-8` → 行为零变化；
   - 模拟 Win7（父进程 `LC_ALL/LANG=zh_CN.GBK`）：`extraEnv={LC_ALL=C.UTF-8, LANG=C.UTF-8} scriptCharset=UTF-8`。
3. 前后对照（模拟 Win7 环境，工作区路径 `普惠授信/公式/中文报告.txt`，与用户现场同形）：
   - 修复前（强制 `PLAIN`，即原行为）：`ok=false`，`ls: cannot access '鏅'$'\256\346''儬鎺堜俊/鍏'$'\254\345''紡': No such file or directory` —— 与用户报告的乱码形态一致；
   - 修复后（走探测）：`ok=true`，输出 `中文报告.txt`。
4. 模拟 Win7 环境下重跑 `execute_chinesePath_ls_ok`、`execute_utf8Output_decoded`、`execute_gbkOutput_decoded`：3/3 通过（注入 locale 后原生 GBK 输出解码未回归）。

### 待现场验收

Win7 机器上执行 `ls "D:/test/普惠授信/公式/"` 与 `cat` 中文文件；麒麟回归一次。

### 顺带发现（本次未修，另行排查）

同一条消息里并行发两次 `Edit` 修改**同一文件**会产生写竞争：本次导致一处改动丢失、且文件末尾
多出一行损坏字节（`0x89`），使文件既非合法 UTF-8 也非合法 GBK（Read 工具报「疑似二进制」）。
提示 Write/Edit 的整文件回写缺少并发保护/原子性保障，建议单独立项排查（本次已手工修正文档）。
