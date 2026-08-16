# minion jar 自举启动（融合 bat + JDK8 提示兜底）设计

日期：2026-08-16
状态：已确认（用户：继续）

## 背景

当前启动链依赖 [minion.bat](minion.bat)：JDK8 探测（MINION_JAVA → JAVA_HOME →
5 个常见安装目录）+ `-Dprism.order=sw` 软件渲染 + `java -jar`。双击 jar 拿不到同等
行为：javaw 无控制台、非 JDK8 直接 `NoClassDefFoundError: javafx/application/Application`。

两个需求：

1. 非 JDK8 启动时弹窗提示建议使用 JDK8（界面兼容性更好），可关闭、不影响软件运行
2. bat 内容融合进 jar：运行时自动开启控制台、自动选 JDK、自动启动最终软件

用户决策：非 JDK8 一律**静默自动切到 JDK8**（不弹窗）；找不到 JDK8 时才在当前 JVM
运行并弹窗兜底；**删除 bat，只交付单一 jar**。

## 方案

### 1. Boot 入口（shade mainClass 改 `com.minion.Boot`）

零 JavaFX 依赖的启动器（只碰 java.lang / java.io / java.util / ProcessBuilder，
不 import 任何 javafx 包），保证无 JavaFX 的 JVM 上也能执行探测逻辑。

```
Boot.main(args):
  1. 设置 prism.order（见 §4；最先执行，保证直启/子进程所有路径生效）
  2. 非 jar 运行（codeSource 是目录 = IDE 开发流）        → 直启 Main
  3. args 含 --relaunched（父进程已自举，防循环）        → 直启 Main
  4. JdkResolver.plan(...) 决策 → 分派：
     RUN_DIRECT     → 直启 Main
     RUN_WITH_WARN  → 置 minion.warn.jdk8 属性 → 直启 Main（§5 弹窗）
     RELAUNCH       → 派生子进程 → exit(0)（§3）
     ERROR_NO_JVM   → stderr + Swing 错误框 → exit(1)（§6）
```

`--relaunched` 子进程直接进 Main，不重复判断，天然无死循环。子进程也会走
Boot.main 第 1 步设置 prism.order，因此派生命令无需带 -D（见 §4）。

### 2. JdkResolver（纯逻辑，可单测）

探测顺序与 bat 逐条一致：

1. 环境变量 `MINION_JAVA`：存在即信任（与 bat `if exist "%MINION_JAVA%" goto :run`
   同契约，不做 jfxrt 校验）
2. `JAVA_HOME`：`%JAVA_HOME%\jre\lib\ext\jfxrt.jar` 存在 → `%JAVA_HOME%\bin\java.exe`
3. 5 个常见目录（与 bat 同清单）：`D:\javame\jdk1.8`、
   `%LOCALAPPDATA%\Programs\Zulu\zulu-8`、`C:\Program Files\Zulu\zulu-8`、
   `C:\Program Files\Java\jdk1.8`、`C:\Program Files (x86)\Java\jdk1.8`
   —— 每个：`<dir>\jre\lib\ext\jfxrt.jar` 存在 → `<dir>\bin\java.exe`

当前 JVM 判定：

- `isJdk8`：`System.getProperty("java.version")` 以 `1.8` 开头
- 当前 JVM 有 JavaFX：`Class.forName("javafx.application.Application", false, loader)`
  捕获 Throwable（只探测类加载，不初始化 JavaFX 工具链）
- 其他 JVM 有 JavaFX：文件存在 `jre\lib\ext\jfxrt.jar`
- 控制台判定：`System.console() != null`（javaw 双击 / 输出重定向为 null）

**决策表**（纯函数 `plan(fromJar, currentVersion, currentHasJfx, foundJava,
currentJavaExe, consoleAttached)` → Plan）：

| 条件 | Plan |
|---|---|
| 非 jar 运行（IDE） | RUN_DIRECT |
| 当前 JDK8 有 FX + 有控制台 | RUN_DIRECT |
| 当前 JDK8 有 FX + 无控制台（双击 javaw） | RELAUNCH(当前 java, 开新控制台) |
| 当前非 JDK8 + 找到 JDK8 | RELAUNCH(找到的 java, 无控制台才开新窗) |
| 当前非 JDK8 + 没找到 + 当前有 FX | RUN_WITH_WARN |
| 当前非 JDK8 + 没找到 + 当前无 FX | ERROR_NO_JVM |

### 3. RELAUNCH 派生

- 无控制台：`cmd /c start "Minion" /D "<jarDir>" "<java.exe>" -jar "<jarPath>" --relaunched <原args>`
  —— 新控制台窗口（java.exe 带控制台，日志可见）
- 有控制台：直接 `ProcessBuilder("<java.exe>", "-jar", ...)`，子进程继承当前控制台，
  不新开窗口
- 父进程 start 后立即 exit(0)
- 命令构造抽为纯方法 `buildCommand(javaExe, jarPath, newConsole, args) → List<String>`，
  可断言单测
- jarPath 取自 `Boot.class.getProtectionDomain().getCodeSource()`（与
  Config.jarDir() 同法；非 jar 运行已在 §1 短路）
- 已知小瑕疵（接受）：终端 `java -jar > log.txt` 重定向输出时 `System.console()==null`，
  会多开一个控制台窗口；该场景罕见，bat 时代也无此路径

### 4. PRISM 渲染参数迁移

原 bat `-Dprism.order=sw`（默认）/ `MINION_PRISM` 覆盖 → 迁移为
`System.setProperty("prism.order", MINION_PRISM ?? "sw")`，在 JavaFX 工具链初始化前
设置（Boot 分派前统一执行），直启与 `--relaunched` 子进程一律生效，派生命令不再带 -D。

### 5. 需求 1 弹窗（兜底 RUN_WITH_WARN）

- 触发条件唯一：当前非 JDK8 + 当前有 JavaFX 能运行 + 系统找不到 JDK8。
  自动切换成功时子进程即 JDK8，属性未置，永不弹窗——符合「自动切不弹窗」决策
- 机制：Boot 置 `System.setProperty("minion.warn.jdk8", "1")` → Main 不感知 →
  MinionApp.start(Stage) 主窗口 show 后检测该属性 → 弹窗
- 实现：`new Alert(AlertType.WARNING, 文案, ButtonType.OK)` +
  `initModality(Modality.NONE)` + `show()`：非模态、可关闭、主界面继续交互
- 复用 Theme 弹窗深色挂载（同 SettingsDialog / ConfirmSheet 方式）
- 文案：「检测到当前运行环境不是 JDK 8（当前版本：{java.version}），且未找到可用的
  JDK 8。建议安装并使用 JDK 8（Oracle JDK 8 或 Zulu 8 FX）运行，以获得最佳界面
  兼容性。此提示不影响使用，可关闭后继续。」

### 6. ERROR_NO_JVM（替代被删 bat 的 exit /b 1 报错）

stderr 打印 + `JOptionPane.showMessageDialog` 错误框（javax.swing，JDK8 自带；
双击 javaw 无控制台时也能看到错误，比 bat 时代体验更好）。文案：「未找到可用的
JDK 8（含 JavaFX）。请安装 Oracle JDK 8 或 Zulu 8 FX 后重试，或将环境变量
JAVA_HOME / MINION_JAVA 指向 JDK 8 安装目录。」

### 7. 删除 bat 与文档同步

- 删除 `minion.bat`
- README：启动方式改为「双击 jar 或 `java -jar minion-0.1.0.jar`」；说明自举行为
  （自动控制台、自动切 JDK8、MINION_JAVA / JAVA_HOME / MINION_PRISM 环境变量仍生效）
- CLAUDE.md「常用命令」中 `minion.bat` 行替换
- docs/ARCHITECTURE.md 如提及 bat 一并更新

## 包位

- `com.minion.Boot`：入口，与 Main 平级，依赖 Main（回调）+ JdkResolver
- `com.minion.JdkResolver`：探测 + 决策纯逻辑，无依赖（含 Plan 枚举 + buildCommand）
- 弹窗逻辑放 MinionApp 内（属性检测 + Alert，数行内联，不单列类）

## 测试

`JdkResolverTest`（临时目录伪造 `jre\lib\ext\jfxrt.jar` + 假 `java.exe`；注入 env map
与候选目录）：

1. MINION_JAVA 命中（存在即用，不做 jfxrt 校验——与 bat 契约一致）
2. JAVA_HOME 含 jfxrt → 命中；不含 → 跳过
3. 5 个常见目录顺序探测、首个命中即停
4. 全部未命中 → null
5. 决策表全分支（fromJar / 版本 / FX / console 组合）

`buildCommand` 断言：有/无控制台两种命令形态、原 args 透传、`--relaunched` 追加。

验证：`mvn test` 全绿；手动冒烟——双击 jar（javaw 无控制台）、终端 `java -jar`、
非 JDK8 机器自动切换、无 JDK8 机器弹窗/错误框。

## 影响面

- 新增：`com.minion.Boot`、`com.minion.JdkResolver`（+ 两个测试类）
- 修改：pom.xml mainClass → `com.minion.Boot`；MinionApp.start 加弹窗检测
- 删除：minion.bat
- 文档：README / CLAUDE.md / ARCHITECTURE.md 同步
- 无新依赖（JDK8 兼容性不变；javax.swing 为 JDK8 自带）

## 实施偏差

- `buildCommand` 实际为 5 参（javaExe, jarDir, jarPath, newConsole, originalArgs）——jarDir 供
  cmd start 的 /D 标志，设计 §3 的 4 参签名过时
- 实施中修正两处简报/计划笔误：buildCommand 单测 size 断言 12→11（cmd start 形态恰 11 元素）；
  JdkResolverTest 需 `import com.minion.JdkResolver.Plan`（嵌套枚举简单名不在同包其他类作用域）
- mintty（Git Bash）下 `System.console()==null`：终端 java -jar 也会被判定「无控制台」而开新
  控制台窗口（真实 Windows 控制台 cmd/PowerShell 行为正确）
- 最终修复：relaunch 失败统一走 errorExit 兜底；prism.order 尊重显式 -D（优先级 显式-D >
  MINION_PRISM > sw 默认）
