# minion 可优化清单（详细版）

> 审查日期：2026-10-30 · 代码基线：`b25cdbf`（工作区干净）
> 审查方式：逐文件通读 + 命令实测统计 + 定向并发/性能推演；**全程只读，未改动任何代码**
> 构建基线：`mvn -o test` → **1262 个用例全绿，耗时 70s**；`target/minion-0.1.0.jar` = 17.7MB

---

## 0. 一句话结论

这个项目的**功能完善度与工程纪律都明显高于同规模个人项目**（测试 : 代码 ≈ 1:1，1262 用例全绿；SQL 只读守卫做到了八视图方言推演；文件写有原子落盘 + 进程内路径锁；Bash 有进程组清杀）。真正的优化空间不在"补功能"，而集中在五类：

1. **锁与线程边界的硬伤**（5 处：确认闸门锁、补全锁、MCP 回调、stdio 写帧、提问无超时——会崩溃或假死）；
2. **权限与信任边界过宽**（7 处：首 token 危险表、越界读批准扩散成全空间免确认、命令白名单永久化、SSRF、SQL 残余写通道、junction 越界读、MCP 工具免确认）；
3. **请求—上下文—落盘链路上的重复计算**（每轮 6~9 次全量 token 扫描、每轮重建 prompt/schema/请求体、每轮全量重写会话 JSON）；
4. **主/子代理双份实现造成的语义漂移**（已出现：子代理不计用量、无轮数上限、错误通道缺失）；
5. **可观测性与工程化缺口**（控制台默认隐藏而 21 处错误只打 stderr，出事无法诊断）。

下面 61 条按影响面排序，每条给出**位置、证据、影响、改法、工作量、验收口径**。

---

## 1. 现状画像

### 1.1 规模与基线（实测）

| 指标 | 数值 |
|---|---|
| 主代码 | 151 个类 / 19,248 行（另有注释与文档注释不计） |
| 测试代码 | 120 个类 / 18,764 行（配比 ≈ 0.97，健康） |
| 测试用例 | 1262 个，0 失败，套件耗时 70s（串行） |
| fat jar | 17.7MB（解压 39MB）：oracle 10.3MB、mysql 5.95MB、fasterxml 5.3MB、flexmark 2.55MB、postgresql 2.06MB |
| 文档 | docs 下 144 篇 md（87 specs + 57 plans），约 3.2MB |
| 资源 | `theme/theme.css` 710 行；config.properties 9 键 |
| 环境 | Maven 3.6.3 / JDK 1.8.0_181 / 平台编码 GBK |

### 1.2 已经做得好的地方（本次审查**不建议动**）

- **JavaFX 渲染已有三级优化**：80ms 合并窗口（`StreamFlushPolicy`）、200 段截断保活（`MAX_SEGS`）、历史分批重放（`REPLAY_BATCH=12`）。方向正确，瓶颈在"全文重算"不在"逐 chunk 重排"。
- **`SqlGuard` 的八视图方言推演**（行内注释 × 可执行注释 × 块注释配对）是被反复加固过的安全边界，算法本身不要重写；但仍有**未被覆盖的具体写通道**（见 P0-11），属于"补规则"而非"推倒重来"。
- **`AtomicFiles` + `PathLocks`**：原子写、`ATOMIC_MOVE` 优先、按路径分条加锁、锁在确认之后获取——这些**约定**是对的，改动时要保住；但降级路径与锁键实现有缺口，见 P1-13。
- **`EventList` 的内存模型是安全的**：`add/rebind/setActive` 全部 `synchronized`，`active/listener` 又是 `volatile`。问题只在"锁内拷贝"与"无上限"，不存在数据竞争。
- **`BashTool` 的进程组清杀**（pid 探针 + `kill -9 -pgid` + `trap 'wait' EXIT`）是踩过坑后的正确实现。
- **`UsageTracker` 的计数是线程安全的**；`Response`/`ResponseBody` 由 try-with-resources 正确关闭。

> 下面所有条目都建立在这个前提上：**不是"这项目烂"，而是"这几处还能更好"**。

---

## 2. 优先级总表

| 编号 | 标题 | 领域 | 影响 | 工作量 | 批次 |
|---|---|---|---|---|---|
| P0-1 | 确认闸门在监视器内等人工点击 | 并发 | 整空间工具链停摆 | M | 1 |
| P0-2 | `[DONE]` 不结束流，最长挂 300s 且回复不入历史 | 协议 | 丢一轮上下文 | S | 1 |
| P0-3 | 畸形 SSE 帧抛出未受检异常，整轮失败 | 协议 | 网关后必现 | S | 1 |
| P0-4 | MCP 回调线程改会话集合与工具注册表 | 并发 | CME 崩溃/工具丢失 | M | 1 |
| P0-5 | stdio 写帧非原子 + 读线程内写 stdin | 并发 | 帧粘连/管道写满挂死 | S | 2 |
| P0-6 | 文件补全锁把全仓库遍历引回 FX 线程 | GUI | 窗口"未响应" | S | 1 |
| P0-7 | 危险命令只比首 token，可绕过确认 | 安全 | 高危操作不弹确认 | M | 2 |
| P0-8 | `StreamHandler.onError` 死契约 + 子代理不判错误态 | 契约 | 错误被当成功报告 | S | 1 |
| P0-9 | 确认授权作用域过宽（越界读批准扩散为全空间免确认 + 首 token 白名单永久放行） | 安全 | 一次授权 = 永久全权 | S | 1 |
| P0-10 | WebFetch 跟随重定向不重校验（SSRF 打内网/云元数据） | 安全 | 内网可被读 | M | 2 |
| P0-11 | SqlGuard 残余写通道 + "第二层只读"不可靠 | 安全 | 只读承诺不成立 | M | 2 |
| P1-1 | 会话落盘每轮全量重写 + 图片 base64 进 JSON | 持久化 | 每轮写 20MB 级 | M | 2 |
| P1-2 | 每轮重建 prompt（读盘）+ tools schema + 整份请求体 | 性能 | 每轮多次 O(n) | M | 2 |
| P1-3 | Token 估算每轮 6~9 次全量扫描且不校准 | 性能 | 长会话卡顿/真实超窗 | M | 2 |
| P1-4 | 流式渲染全文重算（flexmark + 全文测量） | GUI | 长回复 O(n²) | M | 2 |
| P1-5 | 每个 chunk 一个 runLater；状态事件重复投递 | GUI | FX 队列堆积 | M | 3 |
| P1-6 | EventList 无上限 + 重放锁内拷贝；viewCache 无上限 | GUI | 内存无界增长 | M | 3 |
| P1-7 | 启动全量恢复 + 每会话独立 OkHttpClient/线程池 | 启动 | 启动慢、线程多 | L | 3 |
| P1-8 | 页签状态点 Timeline 泄漏（确定性） | GUI | 每回合漏一个无限动画 | S | 1 |
| P1-9 | Bash 无 Git Bash 时每条命令固定 +3s | 工具 | 每条命令多 3 秒 | S | 1 |
| P1-10 | MCP 状态机无 epoch / 忙等 / EOF 不收敛 | 网络 | 连接与子进程泄漏 | M | 3 |
| P1-11 | ReadTool 全文件读入无上限；offset 越界返回空成功 | 工具 | OOM / 空结果 400 | S | 2 |
| P1-12 | Grep/Glob 遍历不校验真实路径（junction 越界读） | 安全 | 沙箱逃逸 | M | 2 |
| P1-13 | 写路径三缺口：原子写降级直写、锁键大小写失配、Bash 输出竞态 | 正确性 | 半截文件 / 丢更新 | M | 3 |
| P1-14 | AskUserQuestion 无超时且共享单槽，可耗尽工具池 | 并发 | 会话假死 / 答案错配 | S | 2 |
| P1-15 | DB 连接面：URL 进提示词、无属性白名单、只读不可靠、CLOB 全量 | 安全 | 凭据泄露 / OOM | M | 2 |
| P2-1 | `runUserTurn` 300 行 + 13 个可变字段 | 架构 | 改动风险高 | L | 4 |
| P2-2 | 主/子循环约 250 行重复且已漂移 | 架构 | 修一处漏一处 | L | 4 |
| P2-3 | 子代理无轮数上限/无止损/用量不记账 | 正确性 | 费用与卡死 | S | 2 |
| P2-4 | 工具取消不 join，`inFlight` 清空 | 并发 | 跨轮重叠写 | M | 3 |
| P2-5 | 压缩批次无上限、摘要不截断、危险区可空转 | 上下文 | 最需要压缩时压缩失败 | M | 3 |
| P2-6 | 无统一日志 + 控制台默认隐藏 + 无全局异常兜底 | 可观测 | 线上不可诊断 | M | 2 |
| P2-7 | 关键超时/上限硬编码不可配 | 配置 | 慢模型无法适配 | S | 3 |
| P2-8 | 重试固定间隔，无退避/抖动/Retry-After | 网络 | 429 放大 | M | 3 |
| P2-9 | `SessionManager` 职责过载 + 装配链重复漂移 | 架构 | 新回调改两处 | L | 4 |
| P2-10 | 三份可中断等待、两份工具执行、五份 UI 工厂 | 结构 | 语义漂移 | M | 4 |
| P2-11 | SSH 凭据明文落盘 + 不校验主机指纹 | 安全 | 凭据泄露 | M | 3 |
| P2-12 | `SqlGuard` 复杂度高、缺性质测试 | 维护 | 改规则易破不变式 | M | 4 |
| P2-13 | 设置窗监听器残留 + `ConfirmSheet` 静态状态吞键 | GUI | 泄漏 + 输入异常 | M | 3 |
| P2-14 | `ToolRegistry` 无 schema 缓存 | 性能 | 每请求重建 | S | 2 |
| P2-15 | JSON 配置存储：固定 tmp 名、无 ATOMIC_MOVE、解析失败重置默认值 | 持久化 | 凭据丢光 | M | 3 |
| P2-16 | 注册表/插件语义：同名覆盖内置工具、gate 使冲突检测失效、钩子死代码 | 架构 | 工具错源 | M | 3 |
| P2-17 | MCP 工具永不确认 + description/schema 直接透传 | 安全 | 写类工具免确认 | M | 3 |
| P2-18 | SSH 客户端可靠性：无心跳、超时复用、编码写死、SFTP 原地覆盖 | 可靠性 | 长任务不稳 | M | 3 |
| P3-1 | pom 缺构建治理（enforcer/compiler/wrapper/版本集中） | 构建 | 换机行为漂移 | S | 3 |
| P3-2 | fat jar 体积治理（locale/versions/ojdbc） | 构建 | 17.7MB | M | 4 |
| P3-3 | 外部 config 新键永不补齐、写入非原子 | 配置 | 升级后行为不一致 | S | 2 |
| P3-4 | `skills.dir` 按进程 CWD 解析 | 配置 | 直启时技能丢失 | S | 2 |
| P3-5 | JDK 探测含开发机路径、MINION_JAVA 不校验 | 自举 | 分发给他人失真 | S | 3 |
| P3-6 | relaunch 不校验子进程、无单实例保护 | 自举 | 双击"没反应"/并发写 | M | 4 |
| P3-7 | 测试无超时护栏、25 处 sleep、无 CI/覆盖率 | 测试 | 挂起与 flaky | M | 3 |
| P3-8 | 三个超大测试类重复装置 | 测试 | 维护成本 | M | 4 |
| P3-9 | 文档：CONVENTIONS 与代码不符、README 体积失真、specs 无归档 | 文档 | 误导新人 | M | 4 |
| P3-10 | 版本管理边界（tools/ 未跟踪、sdd 自忽略、.bak） | 仓库 | 决策不可追溯 | S | 4 |
| P3-11 | 会话列表每 cell 两次全量拷贝 + 每分钟 setAll | GUI | 刷新开销 | S | 4 |
| P3-12 | 无边框窗口 8 个透明 Region 抢占点击 | GUI | 拖窗/滚动条/关闭角失效 | S | 3 |
| P3-13 | 滚动置底靠双层 runLater + `0.999999` 魔法值 | GUI | 贴底抖动 | M | 4 |
| P3-14 | 上传图片在 FX 线程读盘+base64 | GUI | 选图冻结 | S | 3 |
| P3-15 | Bash 脚本落系统临时目录、删除失败静默 | 工具 | 崩漏临时文件 | S | 4 |
| P3-16 | DB/SSH 无并发上限（可打满 max_connections / MaxStartups） | 工具 | 服务端拒绝 | M | 4 |
| P3-17 | Write 恒按 UTF-8 覆盖 GBK 文件、Edit 全局改 CRLF | 工具 | 静默改编码 | S | 3 |

### 2.1 本批已修复（2026-10-30）

> 设计文档：[2026-10-30-optimization-p0-6-p1-3-4-5-6-8-design.md](superpowers/specs/2026-10-30-optimization-p0-6-p1-3-4-5-6-8-design.md)

| 编号 | 修复要点 | 验证 |
|---|---|---|
| P0-6 | `FileSuggester` 去掉全类 `synchronized`（改 `ConcurrentHashMap`，`walk` 全程锁外）；缓存改不可变列表共享（读接口零拷贝）；单次遍历上限 2 万条提前收敛；`Suggestion.lower` 构造期缓存 | `FileSuggesterTest`：不可变/共享、上限截断、无 `synchronized` 方法、并发读不被遍历阻塞 |
| P1-3 | `ContextManager.evaluate` 单次扫描给出全部判定（主/子循环每轮一次）；`calibrate` 用真实 usage 回灌（clamp 0.8~1.5、EMA、可信度闸门）；`TokenCounter` 按 code point 且补假名/谚文/CJK 扩展/emoji；`HARD_LIMIT_RATIO=0.95` 发送前硬闸 | `ContextManagerTest`：evaluate 与兼容接口一致、校准收敛/忽略异常、硬闸口径；`AgentLoopCompactTest`：pinned 占满窗口时发请求前拦下、usage 校准回灌；`TokenCounterTest`：日文/韩文/emoji/代理对 |
| P1-4 | 新增 `StreamingPlainText` 行级增量展平（定稿再用一次精确 `toPlainText` 覆盖）；合并窗口按长度自适应（80/150/250ms）；流式写入跳过布局后双 runLater 精校 | `StreamingPlainTextTest`：增量=一次性、与精确转换逐字一致、围栏/列表/表格/未完成行；`ChatViewFlushPolicyTest`：窗口自适应与回落 |
| P1-5 | 新增 `CoalescingQueue`：后台只入队、FX 线程一次排空整批（事件带视图代数，clear 后丢弃）；running 变化由 3 个 runLater 合并为 1 个 | `CoalescingQueueTest`：100 次入队只调度 1 次、顺序保持、并发不丢 |
| P1-6 | `EventList` 上限 5000 + 绝对游标（`base` 钳制，丢弃不重放、不丢新事件）；新增 `ViewCache` LRU（上限 4，淘汰即 `bind(false)`） | `EventListTest`：上限/基准钳制/绝对游标不重复/clear 重置；`ViewCacheTest`：LRU 淘汰与回调 |
| P1-8 | 页签状态点/Tooltip 每页签复用同一节点（running 只 start/stopPulse，关闭时 stopPulse），不再每次新建 Circle/Timeline | 代码路径复用（`applyTabHead`），无新建节点；`mvn test` 全绿 |

---

## 3. P0 —— 正确性与安全（第 1~2 批）

### P0-1 确认闸门在监视器内等待人工点击
**位置**：`src/main/java/com/minion/core/tools/confirm/ConfirmGate.java:22,29,45,56,65,76`；`src/main/java/com/minion/gui/confirm/GuiConfirmUi.java:39`
**现状**：`check()` 等方法均为 `synchronized`，方法体内直接调用 `ui.ask(...)`；而 `GuiConfirmUi.ask` 用 `q.take()` **无限阻塞**等待用户点击。每个工作空间只有一个 `ConfirmGate` 实例（`SessionManager.buildCtx:283`），被该空间所有会话共享。
**影响**：任一会话弹出确认卡未答复期间，同空间其它会话的 `check` / `checkReadOutside` / `checkWriteOutside` / `readOutsideAllowed` 全部阻塞在监视器上——用户离开电脑时整空间工具链停摆。这与项目自己在 `PathLocks` 上的约定直接矛盾（ARCHITECTURE.md §关键常量注释：「确认会阻塞等用户点击，持锁等待会挂死其他写者」）。
**建议**：状态与交互分离——`synchronized` 只保护 `sessionBypass`/白名单读写的毫秒级临界区，`ui.ask` 移到锁外；如需保证同一时刻只有一张确认卡，用显式串行调度器（单线程队列）而不是监视器。`readOutsideAllowed()` 这类纯查询不应与交互共用同一把锁。
**工作量 / 风险**：M / 低。
**验收**：两个会话并发触发高危工具时，第二个不再被第一个阻塞；关窗/删除会话能中断排队中的确认。

### P0-2 `[DONE]` 只 `continue` 不终止，最长挂 300 秒且回复不入历史
**位置**：`src/main/java/com/minion/core/llm/DeepSeekClient.java:178,181`
**现状**：
```java
while ((line = source.readUtf8Line()) != null) {
    if (!line.startsWith("data:")) continue;
    String data = line.substring(5).trim();
    if (data.isEmpty() || data.equals("[DONE]")) continue;   // ← 只跳过，继续读
```
循环退出条件只有 EOF；首 token 看门狗在第一个增量到达时就已 `cancel`（:196/204/210），此后无任何超时保护。
**影响**：HTTP/1.1 keep-alive 复用、反向代理不关事件流、服务端发完 `[DONE]` 保持连接时，本线程阻塞至 `READ_TIMEOUT=300s` 抛 SocketTimeout → 归为 TIMEOUT。此时 GUI 已经渲染完整回复（用户以为成功），但 `onFinish` 未回调 → `usage==null` → 回复**不入历史**，下一轮上下文缺这一段，且会被长重试逻辑再发一次完整请求。
**建议**：`if ("[DONE]".equals(data)) break;`，把 `[DONE]` 视为正常完成；顺带处理空行事件边界。
**工作量 / 风险**：S / 低。
**验收**：新增单测——MockWebServer 发完 `[DONE]` 后保持连接不关闭，断言 `streamChat` 在 1 秒内正常返回且 `onFinish` 被调用。

### P0-3 畸形 SSE 帧抛出未受检异常，整轮失败且不重试
**位置**：`src/main/java/com/minion/core/llm/DeepSeekClient.java:182,223`
**现状**：`JsonParser.parseString(data).getAsJsonObject()` 在循环内，而循环外只 `catch (IOException e)`。`JsonSyntaxException` / `IllegalStateException` 不是 IOException，直接穿透 `streamChat`；`AgentLoop` 只 catch `LlmException`（:568），最终落到外层 `catch (Exception)`（:753）→ 界面显示「异常: com.google.gson…」，**不重试、不落盘、已流出的内容全部丢弃**。
**影响**：网关/代理在流中插入半截帧、心跳帧（`data: ping`）、HTML 错误页、截断 JSON —— 这些在真实网关后是常态。一旦发生，整轮对话报废。
**建议**：逐帧 try/catch 解析异常，跳过错帧并计数（连续 N 次或首包即坏才升级为 `LlmException` 走既有重试）；已收到增量时按「[DONE] 语义降级完成」而不是抛异常。
**工作量 / 风险**：S / 低。
**验收**：表驱动单测覆盖 `data: ping`、`data: {"choices":[`（截断）、`data: <html>`、连续 5 个坏帧；断言前三种跳过后仍完成、末种转 TIMEOUT/EMPTY 并进入重试。

### P0-4 MCP 回调线程改会话集合与工具注册表
**位置**：`src/main/java/com/minion/gui/session/SessionManager.java:368-375`（写点 `443/543/589/601/664`）；`src/main/java/com/minion/core/tools/ToolRegistry.java:25,34-38,50-74`；`src/main/java/com/minion/core/mcp/McpManager.java:83`
**现状**：MCP 连接线程回调 → `registerMcpToolsToAllSessions` 遍历 `ctxByName`（`HashMap`）与 `ctx.sessions`（`ArrayList`）并调用 `registry.register(...)`；而 FX 线程同时在 `createSession`/`deleteSession`/`renameWorkspace`/`deleteWorkspace` 增删同一批集合，会话工作线程同时在 `registry.schemas()`/`get()` 遍历 `LinkedHashMap tools`。注释自称"只读遍历"，实际是**写**（`tools.put`）。
**影响**：`ConcurrentModificationException`、HashMap 并发扩容导致结构损坏（极端情况死循环）、工具漏注册/被覆盖——表现为"崩溃"或"模型说工具不存在"。ARCHITECTURE.md §MCP 接线明确写了"每轮动态 registry.schemas()"，即该竞态是设计内路径，不是理论风险。
**建议**：①`ToolRegistry` 内部改 `ConcurrentHashMap` + `schemas()` 返回快照，或对 register/读取加同一把锁；②MCP 监听回调只 `Platform.runLater` 投递，遍历与注册都回到 FX 线程；③会话表统一"FX 线程拥有"，后台线程只读快照。
**工作量 / 风险**：M / 中（涉及线程模型约定）。
**验收**：并发压力测试（1 线程反复 register 新工具、1 线程反复 schemas/get）跑 10 万次无异常；MCP 连接完成时删除会话不抛 CME。

### P0-5 stdio 写帧非原子，且读线程内写 stdin
**位置**：`src/main/java/com/minion/core/mcp/MinionStdioTransport.java:283,300-302`（另 `:196` → `:284`）
**现状**：
```java
o.print(json);
o.write('\n');
o.flush();          // 三个独立调用，PrintStream 只保证"单方法"同步
```
`sendJson` 无任何写锁，而调用方既可能是 `callTool`（工具池 4 线程并行）、也可能是读线程（处理服务端请求时回写）。并发时会出现 `{"id":1}{"id":2}\n\n` 这类粘连帧。此外读线程内的写操作没有超时，服务端不读 stdin 时管道写满 → 读线程永久阻塞 → 所有 pending 请求无人完成。
**影响**：MCP 工具表现为随机超时、`Received unknown message id`，极难复现；一次卡死会拖住一个工具线程直到进程退出。
**建议**：单写线程 + 有界队列（读写彻底解耦），或 `synchronized (writeLock) { 一次性写出整帧 }`；所有 stdin 写统一投递，带超时。
**工作量 / 风险**：S / 中（要回归三种传输）。
**验收**：并发调用 50 次 `callTool`，服务端收到的每帧均可独立解析；人为让服务端不读 stdin，断言写操作 5s 内失败而非挂死。

### P0-6 文件补全锁把全仓库遍历引回 FX 线程
**位置**：`src/main/java/com/minion/gui/input/FileSuggester.java:40-83`；`src/main/java/com/minion/gui/input/InputView.java:466,469,478`
**现状**：`load()` 是 `synchronized` 方法，而 `walk(workDir)`（全量递归遍历，注释明确"不设上限"）在**锁内**执行；`listStaleOk`/`isFresh`/`listCached` 共用同一监视器，且由 FX 线程在每次输入字符时调用。
**影响**：首次 `@` 或缓存过期后，后台扫描期间 FX 线程在补全入口上被阻塞直到遍历结束。10 万文件的仓库是数秒级，百万级是数十秒级 → 输入框完全无响应、窗口标题出现「未响应」。这是本次审查中**用户感知最直接**的一条。
**建议**：锁只保护 `caches` 的读写（`ConcurrentHashMap` 或读写锁）；walk 在锁外完成后 put；`listStaleOk` 返回不可变视图避免全量拷贝；给 walk 加条目上限并分页/异步递增展示。
**工作量 / 风险**：S / 低。
**验收**：大仓库上首次 `@` 后立即敲键，FX 线程无 >16ms 的停顿（用 `Platform.runLater` 心跳打点验证）；补全结果仍按 5 分钟 TTL 刷新。

### P0-7 危险命令识别只比首 token，`bash -c` 一类包装可绕过确认
**位置**：`src/main/java/com/minion/core/tools/DangerousCommands.java:38-45`；`src/main/java/com/minion/core/tools/BashTool.java:63-65`
**现状**：只取第一个空白分隔 token 并与 15 个词比较。`bash -c 'rm -rf ~'`、`sh -c`、`cmd /c del /s`、`powershell Remove-Item`、`find . -delete`、`git clean -fdx`、`> /dev/sda` 全部判定为"非危险" → **不弹确认、直接执行**。此外 `confirm.skip=true` 时连这层都没有。
**影响**：高危确认的实际覆盖率远低于设计预期；模型一条 `bash -c "rm -rf ..."` 即可静默删除。这不是理论绕过——模型生成 shell 包装是常见行为。
**建议**：①对解释器形式（`bash/sh/zsh/cmd/powershell -c`）递归解析内部命令串；②按 `;`/`&&`/`||`/`|`/`$()`/反引号切分后逐段判定；③补写重定向到块设备、`find -delete`、`git clean`、`Remove-Item` 等规则；④根本方案：增加「所有 Bash 都需确认一次」的严格模式配置项，把 denylist 作为加速层而非安全边界。
**工作量 / 风险**：M / 低（只增加确认次数）。
**验收**：新增用例表覆盖 12 种包装形态，全部 `isHighRisk==true`；确认弹窗文案显示被解析出的真实危险段。

### P0-8 `StreamHandler.onError` 是死契约，子代理不判错误态
**位置**：`src/main/java/com/minion/core/llm/StreamHandler.java:10`；`src/main/java/com/minion/core/llm/DeepSeekClient.java:294-298`；`src/main/java/com/minion/core/agent/AgentLoop.java:561-564,636-640`；`src/main/java/com/minion/core/agent/SubAgentLoop.java:137,213-226`
**现状**：`DeepSeekClient` **从不调用** `handler.onError`（错误只以抛 `LlmException` 表达），于是 `completeChat` 的 `err[0]`、AgentLoop 的 `finish=="error"`/`err[0]` 分支、SubAgentLoop 的错误提示全部不可达。更糟的是 `SubAgentLoop` 在 `toolCalls` 为空时直接 `return persistReport(content)`，**没有 `"error".equals(finish[0])` 判定**（主循环有）。
**影响**：①错误通道双轨，读代码需两套心智模型，后续在"错误分支"上加的逻辑永不触发（例如"API 400 时清图降级"实际只走异常路径）；②一旦换用会走回调的 `LlmClient` 实现，子代理会把报错前的半截正文当成功报告交回主代理，主代理据此继续。
**建议**：二者择一。推荐**删除 `onError` 回调**，统一由异常 + `interrupted` 表达，并删掉所有 `finish=="error"` 死分支；同时在 `StreamHandler` 接口注释里写死契约。若保留回调，则 `SubAgentLoop` 必须补齐错误判定。
**工作量 / 风险**：S / 低。
**验收**：接口 Javadoc 写明"错误只以异常表达"；`grep onError` 只剩 `LlmException.of`；子代理补一条"注入错误回调后返回失败"的单测。

### P0-9 确认授权作用域过宽（两个独立缺陷）
**位置**：`src/main/java/com/minion/core/tools/confirm/ConfirmGate.java:22-40,65-83,86-105`
**缺陷 A —— 单一布尔位被三处语义共用**：`sessionBypass` 同时被 `check`（高危）、`checkEscapeWrite`（越界写）、`checkOutside`（越界读）读写。用户在**越界读取**弹窗里选"会话放行"时，`checkOutside` 直接 `sessionBypass = true`（:82），此后**同工作空间所有会话**的 `rm`、`Edit`、`Write` 覆盖、越界写全部免确认。`ConfirmGate` 是工作空间级单例（`SessionManager.buildCtx:283`）。
**缺陷 B —— 命令白名单按首 token 永久化**：`addToWhitelist` 把 `DangerousCommands.firstToken(...)` 写入 `config.properties`（:100-101），`isWhitelisted` 只比首个 token（:92）。对 `rm -rf build/` 选一次"永久放行"，就等于把 `rm` 这个**可执行文件名**永久加入白名单——此后 `rm -rf /`、任何盘符任何参数都静默放行，且跨重启生效。
**影响**：一次"读取授权"升级为"永久全权"，且可跨会话、跨重启扩散。这是本次审查中**最容易被忽视的权限扩散**，比 P0-7 的首 token 绕过更隐蔽（用户以为只批准了一次）。
**建议**：①拆成 `readOutsideBypass / writeOutsideBypass / highRiskBypass` 三个独立作用域，越界读永不放行高危；作用域按"会话"而非"工作空间"生效；②删除命令级白名单，或改为"完整命令模式"（可查看、可撤销、带作用域），确认框明确展示"将被记住的确切范围"。
**工作量 / 风险**：S / 低。
**验收**：选"越界读永久放行"后 `rm` 仍弹确认；批准 `rm -rf build/` 后 `rm -rf /` 仍弹确认；白名单可在设置页查看并撤销。

### P0-10 WebFetch 跟随重定向但不重新校验（SSRF）
**位置**：`src/main/java/com/minion/core/tools/WebFetchTool.java:39-43,71-77`
**现状**：`followRedirects(true)`，而内网/回环校验只在**首个请求**发出前做一次。`https://attacker/x` 302 到 `http://127.0.0.1:8080` 或 `http://169.254.169.254/latest/meta-data/` 即可读到本机服务/云元数据，正文回流给模型。DNS rebinding（校验与请求的两次解析结果不同）同样可绕过。内网段也只覆盖 loopback/link-local/site-local，`100.64/10`（CGNAT）、`fc00::/7`（ULA）、`0.0.0.0/8` 未覆盖。
**建议**：`followRedirects(false)` + 手写重定向循环，**逐跳**重新校验；把校验时解析出的 IP 固定到连接（自定义 `Dns`）防 rebinding；补全 CGNAT/ULA/保留段；加 `.callTimeout(...)` 防慢速滴流。
**工作量 / 风险**：M / 低。
**验收**：新增单测——302 到 `127.0.0.1` 被拒；DNS 解析到两个不同地址时请求使用已校验的那个。

### P0-11 `SqlGuard` 残余写通道 + "第二层只读"不可靠
**位置**：`src/main/java/com/minion/core/tools/db/SqlGuard.java:113,190-193,322-331`；`DbExecutor.java:57-64,83-84`
**现状**：
1. **Oracle `EXPLAIN PLAN INTO <表>` 写表**：`EXPLAIN` 在允许首词里；目标检查是黑名单——`EXPLAIN_OPTIONS` 含 `PLAN`/`FOR` 但**不含 `INTO`**，`WRITE_HEADS` 也不含，于是 `EXPLAIN PLAN INTO t_plan FOR SELECT 1` 全部检查通过。Oracle 会把计划行 INSERT 进目标表（先按 statement_id DELETE），只需要对目标表有 INSERT 权限，且发生在 `executeQuery` 返回 ResultSet 之前。
2. **已自认的缺口**：`FORBIDDEN` 只有 7 条短语，PG 的 `SELECT * INTO t2 FROM t`（建表+插入）、MySQL 的 `LOAD_FILE()`、`SELECT DBMS_LOCK.SLEEP(600)` 都会通过（类注释 :100-102 自认前两者）。
3. **第二层只读不可靠**：`c.setReadOnly(true)` 被 `catch (Throwable ignored)` 吞掉，Oracle 明确忽略该设置；`setQueryTimeout` 依赖驱动（MySQL 靠另一连接发 KILL QUERY，需额外权限）；`DriverManager.setLoginTimeout` 是 JVM 全局静态。
**影响**：文档承诺的"三层只读防护"实际只剩 SqlGuard 一层，而这一层有已知写通道。对生产库这是"误写/锁表"级别的事故面。
**建议**：**短期**——EXPLAIN 目标改白名单（跳过解释选项后第一个实词必须是 `SELECT`/`WITH`，直接拒绝含 `PLAN`/`INTO`/`ANALYZE` 的 EXPLAIN）；拦裸 `INTO <表>`（排除 `OUTFILE`/`DUMPFILE`/`@var`）、`LOAD_FILE|LOAD DATA`、`DBMS_|UTL_`；SQL 长度上限（如 100KB）。**中期**——事务级只读（MySQL `SET SESSION TRANSACTION READ ONLY`、PG `default_transaction_read_only=on`）+ 只读账号作为真正的边界；只读/超时设置失败要**报错**而不是静默。
**工作量 / 风险**：M / 中（改动安全边界需强回归）。
**验收**：三条绕过语句各补一条拒绝用例；若目标库不支持只读设置，工具返回明确错误而不是继续执行。

---

## 4. P1 —— 性能与资源（第 2~3 批）

### P1-1 会话落盘每轮全量重写 + 图片 base64 进 JSON
**位置**：`src/main/java/com/minion/core/agent/AgentLoop.java:748,763`；`src/main/java/com/minion/core/storage/SessionStore.java:24-32`；`src/main/java/com/minion/core/llm/ImagePart.java:16`
**现状**：每完成一轮工具调用就 `gson.toJson(整个 Session)` → 写临时文件 → `ATOMIC_MOVE`；`Session` 里包含 `messages`（含 `reasoningContent`、完整工具输出）与 `ImagePart.base64`（单图 5MB → base64 ≈6.7MB，每条消息最多 3 张）。
**影响**：一轮工具循环等于把整份历史重写一遍，总写量与轮数×历史长度成正比（O(n²)）。带 3 张图的会话单轮落盘 ≈20MB；20 轮 ≈400MB 磁盘写 + 同等量级的字符串分配。机械盘/杀软环境下这会直接拖慢流式首字节。
**实测（2026-10-30，本机，真实会话）**：`run/session` 里 253 条消息 / 537k 字符的会话——`gson.toJson` 4.83ms + `getBytes` 1.74ms + 写盘&ATOMIC_MOVE 3.57ms ≈ **10.1ms/轮**（低端机按 3~5× 估 30~50ms/轮，20 轮即 0.6~1s/回合）。文本会话每轮瞬时分配约 1.8MB（String 1.07MB + byte[] 0.69MB），GC 压力可忽略；**图片会话按 40× 放大**（20MB JSON → 每轮约 60MB 瞬时分配 + 20MB 写盘），这才是 GC 尖峰与大卡顿的来源。
**⚠ 另有一处直接卡 UI 的路径**：`SessionListView.java:158`（重命名对话框回调，FX 线程）→ `SessionManager.renameSession:527-530` → `persist:794-802` → `SessionStore.save`，即在 **FX 线程上做整份会话的全量序列化 + 写盘**。文本会话约 10ms 级（可感知），图片会话 400ms~1s（明显冻结）。`createSession:446` 同样在 FX 线程写（空会话，可忽略）。
**建议**：①图片改为独立文件（`session/<空间>/<会话id>/assets/*.png`），JSON 只存相对路径；②落盘节流：仅当消息数变化且距上次 ≥N 秒才写，回合结束/退出必写（:763 已有兜底）；③**把 `renameSession` 的落盘挪到后台线程**（最小改动，半天内可上线）；④进阶：JSONL 追加日志 + 周期性压缩成快照。
**工作量 / 风险**：M / 中（需数据迁移与兼容旧格式）。
**验收**：3 图会话跑 20 轮，落盘总字节 < 5MB；旧格式会话仍可加载。

### P1-2 每轮重建 system prompt（读盘）+ tools schema + 整份请求体
**位置**：`src/main/java/com/minion/core/agent/AgentLoop.java:521,567,598`；`src/main/java/com/minion/core/agent/SystemPromptBuilder.java:80,100-112`；`src/main/java/com/minion/core/llm/DeepSeekClient.java:76-104,159`；`src/main/java/com/minion/core/tools/ToolRegistry.java:59-74`
**现状**：`build()` 内部调用 `loadProjectMd()`，每次 `Files.readAllBytes`；`registry.schemas()` 每个请求重建全部工具 schema（重试再建一次）；`buildRequest` 把全部历史 `toApiJson()` 成 gson 树后 `body.toString()`（整份大字符串，两份副本），重试时全部重做。
**影响**：每轮至少 1 次磁盘读 + 1 次全量 schema 构建 + 1 次全量 JSON 序列化；1000 轮上限下是 1000 次读盘和 1000+ 次全量序列化。上下文 100k token 时单次序列化是几十毫秒级，重试路径翻倍。
**建议**：①`SystemPromptBuilder` 按 (projectMd mtime,size + skills 版本) 缓存 build 结果；②`ToolRegistry` 维护 schema 缓存，注册/门控变更时失效，返回不可变副本；③`Message` 内容不变时缓存 `toApiJson()`；④重试复用同一 `RequestBody`。
**工作量 / 风险**：M / 低。
**验收**：单轮内 `build()` 中文件读次数 = 0（缓存命中）；重试路径 schema 构建次数 = 0。

### P1-3 Token 估算每轮 6~9 次全量扫描，且从不与真实 usage 校准
**位置**：`src/main/java/com/minion/core/context/TokenCounter.java:12-20,27-41`；`src/main/java/com/minion/core/context/ContextManager.java:87-93,169-198`；`src/main/java/com/minion/core/agent/AgentLoop.java:489-501,515,651,727,766-771`
**现状**：`estimate` 逐字符循环；一个压缩检查点内 `shouldCompress` → `worthCompressing`（内部再 `estimate` + `compressibleTokens` 的 `chunkGroups` + 逐组 `estimateMessages`）→ `estimate` 算百分比 → 再 `shouldCompress`，加上 4 处 `pushContextStats` 与轮末统计，单轮 6~9 次全量扫描，每次都新建大量临时 List。
**关键缺陷不只是性能**：估算从不校准——`usage.inputTokens` 只记账（:635），不参与修正；`isCjk` 只覆盖 4 个区段，日文假名/韩文/全角/emoji（代理对按 2 字符 × 0.25 计）严重低估。65% 触发线、85% 危险区、0.13 保留区预算全建立在这个可能低估数倍的估算上，而发送前**没有 `estimate >= max` 的硬拦截**。真实超窗时服务端 400（`retryable=false`）→ 本轮中止，且该会话此后每次重发仍超窗，用户只能新建会话。
**建议**：①用最近一次 `usage.inputTokens / estimate` 求修正系数（clamp 0.8~1.5）回灌；②`isCjk` 补 Hiragana/Katakana/Hangul/CJK Ext-B/全角，代理对按 1~2 计；③`ContextManager` 增加 `evaluate(messages)` 一次返回 used/compressible/take/keep，主循环每轮只调一次；④发送前硬闸（≥95% 强制压缩或拒绝并提示）。
**工作量 / 风险**：M / 中（校准系数需观察真实数据）。
**验收**：日文/emoji 用例估算误差 < 30%；构造"估算 60% 实际 105%"的会话，断言发送前被拦截并触发压缩而不是 400。

### P1-4 流式渲染全文重算：flexmark 全文解析 + TextArea 全文测量
**位置**：`src/main/java/com/minion/gui/chat/ChatView.java:709-715`；`src/main/java/com/minion/gui/chat/MarkdownRenderer.java:82-91`；`src/main/java/com/minion/gui/chat/MessageTextArea.java:91-148`；`ChatView.java:306-309`
**现状**：每次合并窗口 flush 对**累计全文**做 `MarkdownRenderer.toPlainText(...)`（flexmark 解析整段），再 `setText` 全文重排；`relayout()` 又用 `Text` measurer 对全文做一次全量 `setText + getLayoutBounds`，随后双 `Platform.runLater` 里 `lookupAll(".text")` 做 CSS 选择器遍历并精校；`computeMinHeight` 直接返回 `computePrefHeight`，使父容器每次布局都对全部 200 段做 pref 测量。
**影响**：单次 flush 成本与文本长度线性相关、总成本 O(n²)。10 万字符级回复时，尾部每 80ms 一次全量解析 + 两次全文测量 + 一次全文重排 → "越写越卡、末段输入迟滞、滚动掉帧"。
**建议**：①流式阶段本就不需要 markdown，追加式维护 plainText，只在段定稿时 `toPlainText` 一次；②高度测量只针对新增行，或按行数×行高粗算后定稿精校；③合并窗口按文本长度自适应放大（>20k 字符时 150~250ms）；④缓存段高累加值，供 `computePrefHeight` 与截断补偿共用。
**工作量 / 风险**：M / 中（要保住"正文可选可复制"的既有能力）。
**验收**：10 万字符回复全程 FX 帧间隔 P99 < 50ms（`PulseListener` 打点）；渲染结果与当前实现逐字一致。

### P1-5 每个流式 chunk 一个 `Platform.runLater`；状态事件重复投递
**位置**：`src/main/java/com/minion/gui/chat/ChatView.java:320-330`；`src/main/java/com/minion/gui/MainWindow.java:251,254`；`src/main/java/com/minion/gui/input/InputView.java:602`
**现状**：`fxListener.onEvent` 为**每个** CONTENT/THINKING 事件投递一个 FX 任务（渲染合并只发生在任务内部）；同一 running 状态变化被 MainWindow 两处 + InputView 一处重复投递，`SessionListView.refresh()` 内又套一层 runLater。
**影响**：一次 2000 chunk 的回复产生 2000 个 FX 任务，每个都要做 map 查表与脏标记；SSE 快于 FX 消费时队列堆积，切换/滚动延迟；状态事件被放大 3 倍。
**建议**：后台线程把增量写入线程安全缓冲，`AtomicBoolean.compareAndSet(false,true)` 后只投递一次"排空"任务（与既有 `scrollThrottle` 同模式）；状态变化由 `SessionManager` 统一一次投递再分发。
**工作量 / 风险**：M / 中（顺序语义要保住）。
**验收**：一次长回复的 FX 任务数 ≤ 事件数/10；事件顺序与现实现完全一致（用重放对比测试）。

### P1-6 EventList 无上限 + 重放锁内拷贝；viewCache 无上限
**位置**：`src/main/java/com/minion/gui/session/EventList.java:41,60-71`；`src/main/java/com/minion/gui/chat/ChatView.java:338-372`；`src/main/java/com/minion/gui/MainWindow.java:68,310-311`
**现状**：`events` 只增不删（`clear()` 在生产路径**从未被调用**）；`rebind` 在持有监视器时 `new ArrayList<>(events.subList(from, n))` 整段拷贝；视图侧 `MAX_SEGS=200` 只删 Node 不删事件；`viewCache` 只增不淘汰，仅在工作空间切换/删会话/关页签时清。
**影响**：常驻内存随时长无界增长（每条 chunk 一个 `Ev` + 文本）；后台跑了 1 小时的会话切回时，FX 线程在持锁状态下拷贝数万对象，期间工作线程 `add()` 全部阻塞（流式被卡住），随后 12 条/帧重放 → 数秒持续布局。打开 20 个会话 = 最多 4000 个 `MessageTextArea` 常驻。
**建议**：①`EventList` 设容量上限（如 5000 条），轮末把同主人的 CONTENT 合并成一条；②`rebind` 的 `from` 取 `max(from, size-cap)`，锁外渲染；③`viewCache` 改 LRU（上限 3~5），淘汰时 `bind(false)` 并把游标存到独立 Map。
**工作量 / 风险**：M / 中（上游"截断保活"语义要同步）。
**验收**：连续运行 2 小时的会话，`EventList` 条数与内存稳定在上限内；切回延迟 < 300ms。

### P1-7 启动全量恢复会话 + 每会话独立 OkHttpClient/线程池
**位置**：`src/main/java/com/minion/gui/session/SessionManager.java:194-200,206-272`（`newLlm` 在循环内 :223；prompt 构建两次 :225-232）；`src/main/java/com/minion/core/llm/DeepSeekClient.java:47,70-73`；`src/main/java/com/minion/core/agent/AgentLoop.java:96`；`src/main/java/com/minion/gui/session/SessionHandle.java:74`
**现状**：启动时对每个工作空间的**每个**历史会话：`store.load`（全量 JSON 解析）→ `new DeepSeekClient`（独立 `OkHttpClient` + 独立 watchdog 调度器）→ `new AgentLoop`（7+ 个工具实例 + 4 线程池）→ `new ContextManager` → 构建两次 system prompt（一次估算 token、一次交给 loop）。
**影响**：20 个会话 = 20 个连接池 + 20 个调度器 + 140 个工具实例 + 40 次 prompt 构建；启动慢（秒级）、常驻线程与内存偏高、`applyModelChanged` 时一次性重建同样数量。
**建议**：①懒恢复：启动只读元数据，首次激活时再构造（配合 P3 的 meta 索引）；②`OkHttpClient` 进程级共享（按超时/代理解析分组），`close()` 语义降级为"取消本实例登记的在途 Call"；③工具池按需扩容或进程级共享；④prompt 只构建一次。
**工作量 / 风险**：L / 高（影响整个会话生命周期模型）。
**验收**：50 个历史会话冷启动 < 1.5s；线程数不随会话数线性增长。

### P1-8 页签状态点 Timeline 泄漏（确定性）
**位置**：`src/main/java/com/minion/gui/MainWindow.java:576-587,644-647`；`src/main/java/com/minion/gui/StatusDot.java:29-46`
**现状**：`updateTab` 每次 `t.setGraphic(runningIndicator(h))` → `StatusDot.create(running)` 新建 `Circle`；`running` 为真时启动 `INDEFINITE` Timeline 并把 Timeline 挂在节点属性上。替换 graphic 时**从不调用 `StatusDot.stopPulseIn(old)`**（`SessionListView.updateItem` 里有这一步，页签路径漏了）。`StatusDot` 的类注释已经写明「节点被丢弃前必须 stopPulseIn，否则每次重建状态点都泄漏一个无限动画」。
**影响**：每回合 running 变化 2 次 + 标题变化 1 次 → 每回合泄漏 1~2 个永不停止的 Timeline（JavaFX pulse 注册表强引用旧 Circle）；长会话几十回合后 FX 线程持续做无效动画，内存与 CPU 双涨。顺带 `new Tooltip(title)`（:581/593）也是每次新建。
**建议**：替换 graphic 前 `StatusDot.stopPulseIn(t.getGraphic())`；更彻底：每个 tab 复用一个 Circle，只切换 `startPulse/stopPulse`；Tooltip 复用只 `setText`。
**工作量 / 风险**：S / 低。
**验收**：跑 20 回合后 `Circle` 实例数不增长（弱引用/堆直方图验证）。

### P1-9 Bash 在无 Git Bash 环境下每条命令固定 +3 秒
**位置**：`src/main/java/com/minion/core/tools/BashTool.java:100-107,137,256-269`
**现状**：pid 探针脚本**总是**写入（:106），但 `shell == null` 时实际执行的是 `cmd /c <command>`（:208），脚本从不运行 → 探针文件永不生成 → `awaitPid` 轮询直到 3 秒超时返回 -1。
**影响**：Windows 上没装 Git Bash / PATH 探测失败 / 注册表查不到时，**每条** Bash 命令固定多 3 秒。这是"模型每步都要跑命令"场景下最容易被误认为"模型慢"的性能损耗。
**建议**：`shell == null` 时跳过探针写入与 `awaitPid`；或首次 200ms 读不到就放弃等待（探针只用于超时组杀，缺它只是降级为 `destroyForcibly`）。
**工作量 / 风险**：S / 低。
**验收**：模拟无 Git Bash（`shell=null`）路径，单条命令端到端耗时下降 ≥2.9s。

### P1-10 MCP 状态机无 epoch / 重连 10s 忙等 / EOF 不收敛状态
**位置**：`src/main/java/com/minion/core/mcp/McpManager.java:14,66-70,131-137,147-150`；`src/main/java/com/minion/core/mcp/MinionStdioTransport.java:142-147`
**现状**：①`doConnect` 完成时无条件 `clients.put(...); state=CONNECTED`，不校验这次连接是否已被 `disconnect` 作废 → 设置页"断开"可能被静默"复活"，被覆盖的旧 client（stdio 子进程 + 2 个读线程）永不释放；②`reconnect` 以 `while + Thread.sleep(50)` 忙等最多 10s，而底层单次连接预算是 3×120s + 退避 ≈ 6 分钟，两者完全不一致；③stdio 进程 EOF 时只 `failPendingRequests`，不通知管理器置 DISCONNECTED、不清 tools；`checkHealth()` 全仓无人调用。
**影响**：UI 仍显示"已连接 + N 个工具"而实际已死；下一次工具调用要等满 `CALL_TIMEOUT_MS=120s` 才报错，期间该工具线程被占用；频繁断开/重连会累积僵尸子进程。
**建议**：①引入 generation/epoch：connect 启动时取 token，完成时在锁内校验 token 与 state 才发布，否则 `client.close()`；put 前若已有旧 client 先关；②`reconnect` 改为 `CompletableFuture`/条件变量等待，并与连接预算同源；③transport EOF 回调管理器置 DISCONNECTED + 清表；`call` 前 `checkHealth()`。
**工作量 / 风险**：M / 中。
**验收**：连接过程中点"断开"，最终状态为 DISCONNECTED 且无残留子进程；子进程被杀后 1s 内 UI 变红、下次调用不再等 120s。

### P1-11 `ReadTool` 全文件读入无上限；offset 越界返回"空成功"
**位置**：`src/main/java/com/minion/core/tools/ReadTool.java:143-152,182-192`
**现状**：`TextFiles.readAllLines(p)` 把**整个文件**读进内存后才按 `offset/limit` 切片——Grep 有 5MB 上限，Read 没有任何上限。另外 `offset` 越过 EOF 时，切片循环不执行、两个"提示"分支都不进入，最终 `return ToolResult.success(sb.toString())` 返回**空字符串**；而 `agent.emptyOutput.placeholder` 默认 false。
**影响**：读几 GB 的日志/数据文件 → OOM 或长时间占用 4 线程池（会话假死）；空 tool 消息正是项目注释里所述"会触发服务端稳定拒绝、重试到超时"的形态。
**建议**：先 `Files.size` 判断，超阈值（如 20MB）直接报错并建议用 Bash 分页/head/tail；改用 `BufferedReader` 跳过 offset 行、只读 limit 行；`offset >= 总行数` 返回 error 并给总行数与建议 offset；空文件返回 `(空文件)`。
**工作量 / 风险**：S / 低。
**验收**：读取 2GB 文件 3 秒内返回明确错误且堆不增长；offset 越界返回 error 而非空串。

### P1-12 Grep/Glob 遍历不校验真实路径（junction 越界读）
**位置**：`src/main/java/com/minion/core/tools/WalkBudget.java:78-93`；`GrepTool.java:130,158`；`GlobTool.java:114-137`
**现状**：`enterDir` 算出了 `real = dir.toRealPath()`，但**只用于目录环去重**（`seenDirs.add(real)`），从不与放行根比较；随后 Grep 直接整文件读取（`TextFiles.readAllLines(file)`）。`WalkBudget` 的类注释自己写明"Java 8 在 Windows 不把 junction 当符号链接，会被递归进入"。
**影响**：工作区内存在 junction 时，Glob 会列出区外文件名、Grep 会返回区外文件**内容**——`errorIfOutsideRead` 的单路径守卫被完全绕过，属于沙箱逃逸。
**建议**：预先计算允许根的 real 集合；`enterDir`/`visitFile` 对 `real` 比对，不在集合内则 `SKIP_SUBTREE`；Glob 多根场景按 real root 而非词法根比对。
**工作量 / 风险**：M / 中。
**验收**：在临时目录构造指向区外的 junction，Grep/Glob 均不返回其内容；正常目录结果不变。

### P1-13 写路径的三个缺口（原子性、串行化、输出竞态）
**位置**：`AtomicFiles.java:47-63,66-85`；`PathLocks.java:18-31,41-43`；`BashTool.java:166-180,198`；`TruncatedOutput.java:51-63,82-108`
**现状**：
- **A 原子写降级为破坏性直写**：tmp 写失败时 `Files.deleteIfExists(tmp); Files.write(abs, data);` —— 先 TRUNCATE 目标，写到一半失败则目标已毁，与"读者要么看到旧文件要么看到新文件"的承诺相反；`verifyLength` 在替换后才校验，识别不出等长并发写；全链路无 `force(true)`。
- **B 锁键在 Windows 上大小写失配**：`PathLocks` 用 `toAbsolutePath().normalize()` 做 key，`C:\WS\a` 与 `c:\ws\a` 落到不同条带锁（junction/短名同理）——恰好在"同轮并行 Edit 丢更新"这个锁本要解决的场景失效；等待上限 30s 且不可中断。
- **C Bash 输出竞态**：kill 后 `reader.join(3000)` 仍可能失败，随后直接 `out.finish()`，与 reader 线程的 `append` 并发修改 `StringBuilder`、并可能删除正在写的落盘文件。
**影响**：极端路径下留下半截文件/丢更新/输出截断乱码——都属于"静默数据损坏"，比崩溃更难查。
**建议**：①降级只对明确的"路径过长"错误生效，其余上抛；直写前先备份旧文件，`force(true)` 后再 move；②锁 key 改为"最深已存在祖先的 realpath + 文件名小写"；等待超时降到 5s 且可中断；③kill 后 `process.getInputStream().close()` 强制解除阻塞，确认 reader 已死再 `finish()`，否则返回"输出未完整"标记。
**工作量 / 风险**：M / 中。
**验收**：模拟磁盘满时目标文件保持旧内容；大小写不同的同一路径命中同一把锁；人为让 reader 阻塞时 Bash 仍能返回且不抛异常。

### P1-14 `AskUserQuestion` 无超时且共享单槽
**位置**：`src/main/java/com/minion/core/tools/AskUserQuestionTool.java:128-148`
**现状**：`fut.get()` **无超时**；并发调用共享同一个 `pending` future（:131"同轮多次调用共享同一 future"），两个不同问题会拿到同一个答案；用户不回答且未触发中断时工具线程永久阻塞。
**影响**：4 个这样的调用即可占满 4 线程工具池 → 整个会话假死（只能关窗）；同轮两个提问的答案可能被错配给另一个问题。
**建议**：`get(timeout)` 超时返回"用户未回答，请改述或继续"；每次调用独立 future（或显式拒绝并发提问）；`complete(answer)` 带 callId 校验归属。
**工作量 / 风险**：S / 低。
**验收**：不回答时 60s 内返回超时结果、线程池不被占死；并发两个提问各自拿到自己的答案。

### P1-15 DB 连接面：URL 进提示词、无属性白名单、只读不可靠、大字段物化
**位置**：`DbTool.java:60`；`DataSourceValidator.java:41`；`DbType.java:15`；`DbExecutor.java:57-64,83-84,104-106`；`DbExport.java:60`
**现状**：`description()` 每轮重新生成并把**完整 JDBC URL** 写进去（URL 可以内嵌 `user/password`，Oracle thin 语法 `scott/tiger@host`、MySQL `?user=&password=`）；URL 校验只有 `startsWith("jdbc:")`，允许 `allowLoadLocalInfile=true`、`autoDeserialize=true`、`allowMultiQueries=true` 这类危险属性；MySQL 模板默认 `useSSL=false`；`setReadOnly(true)` 被 `catch (Throwable ignored)` 吞掉；CLOB 用 `getSubString(1, 全文长度)` 一次性物化、`shouldExport` 在完整字符串之后才判断；BLOB 走 `String.valueOf(byte[])` 渲染成 `[B@hash`（内容丢失且不触发导出）。
**影响**：①URL 内嵌凭据会以明文进入第三方 LLM 请求（比明文落盘更隐蔽）；②危险连接属性可被诱导读本地文件/反序列化（RCE 面）；③默认关 TLS；④大字段 OOM。
**建议**：description 只输出"类型 + 数据源名 + host/库名"，不输出 URL；按 `DbType` 解析 URL 并对连接属性做**白名单**（拒绝 `user/password/allowLoadLocalInfile/autoDeserialize/allowMultiQueries/allowUrlInLocalInfile`）；默认 `sslMode=PREFERRED` 以上；CLOB/BLOB 用 `getCharacterStream()/getBinaryStream()` 按上限流式读并标注截断，BLOB 用 Base64 或大小说明。
**工作量 / 风险**：M / 中。
**验收**：提示词中出现 URL 的用例断言失败；含 `allowLoadLocalInfile` 的 URL 被拒绝；20MB CLOB 读取不 OOM 且标注截断。

---

## 5. P2 —— 架构与可维护性（第 3~4 批）

### P2-1 `runUserTurn` 300 行 + 13 个可变状态字段
**位置**：`src/main/java/com/minion/core/agent/AgentLoop.java:470-772`（状态字段 34-82；`final X[]` 占位符 526-532）
**现状**：单方法内三层嵌套循环（主 `while` :484 / 重试 `while(true)` :586 / 工具收集 `for` :691）、8 处 `break`、4 处 `continue`，同时承担：压缩决策、请求构建、流式回调、长重试、图片降级、空回复重试、工具批调度、止损注入、落盘、统计行。13 个可变字段 + 3 个不同锁保护的集合。`final List<ToolCall>[] toolCalls` / `final boolean[] inRetry` 这类数组占位符是 lambda 捕获的权宜写法。
**影响**：所有退出路径都依赖尾部的 `scrubHalfTurn() + persistSession() + onStatsLine`（:761-771）兜底，新增一个 `break` 就可能漏配对/漏落盘（历史上已踩过）。无法对"重试/工具批/压缩"分别写单测。
**建议**：抽 `TurnState`（rounds/retries/emptyReplies/连续失败）、`LlmTurnRunner`（流式 + 长重试，返回 `Outcome{content,thinking,toolCalls,usage,finish,error}`）、`ToolBatchExecutor`（提交/等待/取消/补 UI 结果）、`ContextGuard`（压缩决策 + 提示）、`TurnFinalizer`（清半轮 + 落盘 + 统计）。`runUserTurn` 收敛到 ~50 行编排。
**工作量 / 风险**：L / 中（1262 个用例是保障网）。
**验收**：`runUserTurn` ≤ 80 行；`LlmTurnRunner`/`ToolBatchExecutor` 各有独立单测；全量测试保持绿。

### P2-2 主/子循环约 250 行重复，且已语义漂移
**位置**：`AgentLoop.java:526-633,774-817,855-884` vs `SubAgentLoop.java:114-212,328-341,356-392`；`ContextCompressor.java:90-103`
**现状**：同构代码至少 5 组——流式 handler、瞬时错误长重试、零增量闸门、可中断等待（**三份**：`AgentLoop.sleepWithInterruptCheck` / `SubAgentLoop.sleepWithInterruptCheck` / `ContextCompressor.sleepWithInterruptCheck`）、工具执行（parse→confirm→execute→gate→UI）。
**已发生的漂移**：子代理缺 `finish=="error"` 判定、缺 usage 记账、缺轮数上限、缺图片降级/空回复重试/卡住提醒。
**影响**：改一次重试策略要同步三处；`ContextCompressor` 的注释已经承认"避免两套重试策略漂移"，但主/子请求重试仍是两套。
**建议**：提取 `StreamingLlmCall`（统一 handler/零增量闸门/长重试/中断复位）、`Interruptible.wait(ms, BooleanSupplier)`、`ToolCallExecutor`，两个循环只保留各自的停止条件与结果处理。
**工作量 / 风险**：L / 中。
**验收**：重复代码行数下降 ≥200；主/子两路共用同一 `RetryPolicy` 单测。

### P2-3 子代理无轮数上限/无止损/用量不记账
**位置**：`SubAgentLoop.java:86`（`while (true)`）、`:114-138`、`:235-242`
**现状**：主循环有 `roundLimit=1000`（:485）与 `STUCK_THRESHOLD=30`（:738），子代理**两者都没有**；`usage[0]` 只在 `onFinish` 存下来用于判空，**从不 `record`** 到 `session.usage`。
**影响**：模型反复调用同一失败工具时子代理永不退出（只能手动停止或等压缩失败）；`/tokens` 与统计行长期低报——子代理往往是 token 消耗的大头，成本判断失真。
**建议**：抽 `TurnBudget`（roundLimit / STUCK_THRESHOLD / 空回复重试）主/子共用，子代理默认更小（如 50 轮、连续 10 次失败）；构造器注入 `UsageTracker`（或 `Consumer<Usage>` 回调），`onFinish` 后统一记账，主/子共享同一实例。
**工作量 / 风险**：S / 低。
**验收**：子代理超过 50 轮自动停止并提示；一次带 3 个子代理的会话，`/tokens` 之和与抓包统计误差 < 5%。

### P2-4 工具取消后不 join，`inFlight` 被清空
**位置**：`AgentLoop.java:700-708,722-726`；`AgentLoop.java:679-688`
**现状**：用户停止时对剩余 future 逐个 `cancel(true)` 后直接 `break`，`finally` 里无条件 `inFlight.clear()`；`cancel(true)` 对不响应中断的工具（SSH/DB/大文件落盘）只是置标志。另外提交循环在 `try` 之外，`pool.submit` 抛 `RejectedExecutionException`（会话已删、池已关）时已提交的 future 不会被清理。
**影响**：本轮随即 `persistSession` 收尾，下一轮/下次发送可能在旧工具仍在写同一路径时开始；`inFlight` 已清空，后续 `interrupt()` 无法再取消它们。`PathLocks` 只在单个工具内部加锁，无法保护跨批/跨回合的重叠写。
**建议**：取消后对本批做短时限 `get`/`awaitTermination`，或在全部终止前不进入下一轮；`inFlight` 只移除已结束项；提交循环移进 `try/finally`；批次级写锁（如需要）。
**工作量 / 风险**：M / 中。
**验收**：停止后立即再次发送，断言两次写操作不重叠（用临时文件时间戳探针）。

### P2-5 压缩批次无上限、摘要不截断、危险区可每轮空转
**位置**：`src/main/java/com/minion/core/context/ContextManager.java:20,144,195-198,214-224`；`AgentLoop.java:489-519`
**现状**：①`SUMMARY_MAX_CHARS=5000` 只拼进 system 提示，`callLlm` 返回 `s.trim()` **不检查长度**——摘要消息在 `chunkGroups` 里被永久豁免（:103），只能作为 `existingSummaryText` 喂给下次压缩，越滚越大；②`buildBatch(groups, 0, take)` 把 take 组全量拼成**一次** `completeChat`，危险区 take≈全部历史，压缩请求本身可能先超窗 → 400 非瞬时 → 本轮中止，形成"最需要压缩时压缩必失败"；③危险区（≥85%）**无条件**返回 true，绕过 5% 收益门槛，若保底组+常驻内容已占满，每轮仍会调一次压缩 LLM。
**影响**：压缩成本失控；危险区可能出现"每轮压一次、每轮都不降"的空转。
**建议**：①返回前截断到 5000 字（注意代理对）；②`buildBatch` 设输入上限（如 40%×max），超限分批/分层摘要；③压缩后若 `estimate` 下降 <5% 则本回合禁止再次自动压缩（保留手动 `/compact`）；④压缩前先截断最新超长工具结果。
**工作量 / 风险**：M / 中。
**验收**：构造"危险区且保底组占满"的会话，断言一轮只触发一次压缩、且不再无限重试；压缩请求 token 数 ≤ 40%×max。

### P2-6 无统一日志 + 控制台默认隐藏 + 无全局异常兜底
**位置**：`Boot.java`/`JdkResolver.java:136-152`（`boot.console` 默认 false → javaw 无控制台）；21 处 `System.err.println`（`Config.java:45,76,141,176`、`WorkspaceManager.java:218,228`、`ModelManager.java:158,168`、`McpStore.java:59,68`、`ToolStore.java:51,109,118`、`SkillManager.java:49,145`、`OutputDump.java:28`、`TruncatedOutput.java:45`、`SystemPromptBuilder.java:109`、`MainWindow.java:374`、`ConfirmSheet.java:64`、`Boot.java:93`）；`AgentLoop.java:753-755` 只把 `getMessage()` 抛给 UI
**现状**：自身代码零 slf4j 使用（唯一一处是库内部）；无日志文件、无时间戳/线程/会话上下文；无 `setDefaultUncaughtExceptionHandler`（全仓 grep = 0）；`catch (Exception e) { ui.onError(errorText("异常: ", e)); }` 把堆栈彻底丢掉。
**影响**：用户报"任务执行异常: null"时**没有任何线索**——没有日志文件、没有堆栈、控制台已被 javaw 隐藏。这是排障成本最高的一类技术债。
**建议**：引入集中 `Log`（`slf4j-simple` 已在依赖里，或自写 20 行文件 logger），落 `<jarDir>/logs/minion-YYYYMMDD.log`（按天轮转、总量上限），异常处记录堆栈；启动时打印版本/JDK/生效配置摘要；安装 default uncaught handler；GUI 提供"打开日志目录"。
**工作量 / 风险**：M / 低。
**验收**：制造一次工具 NPE，日志文件里能查到完整堆栈 + 会话 id + 工具名；日志目录总量不超过配置上限。

### P2-7 关键超时/上限硬编码不可配
**位置**：`DeepSeekClient.java:29-30,37`（30s / 300s / 60s）；`AgentLoop.java:36,74,77`（轮数 1000 / 卡住阈值 30 / 池宽 4）；`BashTool.java:28`；`SubAgentLoop.java:29`；`McpManager.java:14`
**现状**：全部为编译期常量或 public 可变字段，`config.properties` / `model.json` 均无对应项；`AgentLoop.threads` 更是个**死旋钮**——池在构造器里已按 4 建好（:96），之后改 `threads` 永远无效。
**影响**：内网慢网关需要更大首 token 超时只能改代码重发版；长思考模型 60s 被误判超时；弱机器想调小池宽无法做到。
**建议**：收敛到 `AgentSettings`（由 `config.properties` 注入，保留现值默认），删除 `threads` 字段或改为构造参数；`model.json` 增加 `connectTimeoutMs/readTimeoutMs/firstTokenTimeoutMs`。
**工作量 / 风险**：S / 低。
**验收**：改配置文件即可让首 token 超时变 180s；`threads` 字段不存在或生效。

### P2-8 重试固定间隔，无退避/抖动/Retry-After
**位置**：`src/main/java/com/minion/core/agent/RetryPolicy.java:16-17,31-33`；`AgentLoop.java:586-610`；`SubAgentLoop.java:157-184`
**现状**：429/超时/网络固定 5s，500 类固定 30s；墙钟 12 分钟。无指数增长、无抖动；响应头 `Retry-After` 完全没读（`LlmException` 只带 body/httpCode）。主/子/压缩三处各有一份重试循环。
**影响**：429 场景 12 分钟可发约 144 次请求，容易被网关判为滥用并加重限流；多会话同时 429 会同步重试（惊群）；忽略 `Retry-After` 属于协议层面的不礼貌。
**建议**：指数退避（5s→10s→20s，上限 30~60s）+ 随机抖动 ±20%；解析 `Retry-After`；把"耗尽判定"移到 sleep 之前；抽 `RetryExecutor` 供三处共用。
**工作量 / 风险**：M / 低。
**验收**：连续 429 时相邻重试间隔递增且带抖动；`Retry-After: 10` 时等待 ≥10s。

### P2-9 `SessionManager` 职责过载 + 恢复/新建两条装配链重复且已漂移
**位置**：`src/main/java/com/minion/gui/session/SessionManager.java:61-830`（56 个方法；restore :206-272 / create :392-451）
**现状**：一个类同时是：会话 CRUD、工作空间 CRUD 与迁移、线程池与 okhttp 生命周期、`ToolRegistry` 工厂、MCP 工具补注册、模型热更新、事件路由（9 个 notify*）、技能快照缓存。恢复与新建两条链各写一遍 4 个 controller 监听器（`setAskStateListener` / `setCompressingStateListener` / `setRetryStateListener` / `setContextStatsListener`），差异只在 `loop.restoreSession(s)` 与 `store.save`。
**影响**：新增一个会话级回调要改两处，已经出现语义漂移；任何一处漏改就是"某些会话没有某状态"。
**建议**：抽 `SessionFactory`（llm/registry/prompt/loop 装配）与 `SessionEventWiring.wire(h, controller)` 单点；`applyModelChanged` 独立为 `ModelPropagator`；`SessionManager` 只保留生命周期 + 工作空间上下文。
**工作量 / 风险**：L / 中。
**验收**：恢复/新建两条链的监听器装配代码只剩一处；`SessionManager` < 400 行。

### P2-10 结构化重复：三份可中断等待、两份工具执行、五份 UI 工厂
**位置**：三份等待 `AgentLoop.java:810-817` / `SubAgentLoop.java:334-341` / `ContextCompressor.java:90-103`；两份工具执行 `AgentLoop.java:855-884` / `SubAgentLoop.java:356-392`；UI 工厂 `SettingsDialog.java:146-149,371-374`、`ToolsPane.java:182-186`、`DataSourceDialog.java:189-193`、`SshConnectionsDialog.java:295-297`、`ConfirmSheet.java:197-208`
**现状**：`while (now < end) { if (flag) return false; Thread.sleep(min(100, end - now)); }` 抄了三遍；`ghostButton` 五处各写一遍；"确认 Alert 去图标 + 居中 + 深色"四行样板在 `MainWindow` 两处、`WorkspaceListView`、`SettingsDialog` 多处重复；表单标签宽度已漂移（150px vs 160px）。
**影响**：样式改动需同步 5+ 处且已经不同步；语义细节（如 `Math.max(0, …)` 防负 sleep）只在其中一份里有。
**建议**：抽 `Interruptible.wait(...)`、`ToolCallExecutor`、`gui/ui/UiKit`（`ghostButton/formRow(labelWidth)/dangerConfirm/iconButton`）。
**工作量 / 风险**：M / 低。
**验收**：上述三组重复各只剩一处实现；标签宽度统一由常量控制。

### P2-11 SSH 凭据明文落盘、不校验主机指纹
**位置**：`src/main/java/com/minion/core/tools/ssh/SshConnection.java:16,25-30`；`SshExecutor.java:33,78-82`；`ToolStore`（tools.json）
**现状**：`password` 与 `passphrase` 作为普通字段经 gson 明文写入 `tools.json`；`StrictHostKeyChecking=no`（注释说明是刻意选择，限内网/测试）。
**影响**：同机任意进程、备份工具、截图、日志都能拿到生产服务器密码；中间人攻击无感知。这是本项目**唯一处理真实生产凭据**的地方，风险等级高于其它模块。
**建议**：Windows 用 DPAPI（`CryptProtectData`）加密后再落盘，或在首次保存时提示"将明文保存"并写入最小文件 ACL；UI 只显示掩码；异常信息禁止回显密码；至少增加 `known_hosts` 记录的"首次信任 + 变更告警"模式。
**工作量 / 风险**：M / 中（涉及 JNA/系统 API 或第三方库，JDK8 约束下建议纯 JNI 或 `cmdkey` 方案评估）。
**验收**：`tools.json` 中密码字段不可直接读出；UI 中不回显明文。

### P2-12 `SqlGuard` 复杂度高、缺性质测试
**位置**：`src/main/java/com/minion/core/tools/db/SqlGuard.java`（703 行，8 视图 × 5 项检查 + 递归可执行注释）
**现状**：正确性完全依赖 106 行注释里的人工推演；每一轮加固都来自"上一轮漏了一份读法"。
**影响**：新增方言/规则极易破坏既有不变式；新人门槛极高；改动无法量化验证。
**建议**：算法不动，补**性质测试**——随机生成含注释/分号/大小写/可执行注释的语句，断言"凡某方言可执行的写动词必被拒、凡白名单只读语句必被接受"；把不变式抽成可执行断言；输出一份 `docs/sql-guard.md` 说明八视图模型。
**工作量 / 风险**：M / 低（只加测试）。
**验收**：性质测试随机生成 ≥2000 条语句，反例为 0 或已登记豁免。

### P2-13 设置窗监听器残留 + `ConfirmSheet` 静态状态吞键
**位置**：`src/main/java/com/minion/gui/dialog/SettingsDialog.java:356-364`；`ToolsPane.java:69-84`；`ConfirmSheet.java:41-46,104-122,144-147`
**现状**：①设置窗靠"下一次事件到达时发现 `getScene()==null`"自注销——关窗后没有后续事件就永久残留（`McpManager`/`ToolPluginManager` 强引用整个 ListView/DialogPane 图），反复开关线性累积；②`ConfirmSheet` 用 `static StackPane host` + `static boolean showing`，并 `scene.addEventFilter(KeyEvent.ANY, ...)` 后 `e.consume()` 掉除 Enter/Esc 外的**所有**按键（含 Ctrl+C 复制、输入法切换）；`host.getScene()` 为 null 时 `addEventFilter` 会 NPE。
**建议**：①`d.setOnHidden(...)` 或 `sceneProperty` 变 null 时确定性注销；②`ConfirmSheet` 改实例组件挂在 MainWindow（去 static），过滤器只拦 Tab/方向/空格并放行复制与系统快捷键，`scene==null` 时直接拒绝并回调。
**工作量 / 风险**：M / 低。
**验收**：连续开关设置窗 20 次，监听器数量不增长；确认卡显示时 Ctrl+C 可用。

### P2-14 `ToolRegistry` 无 schema 缓存（并入 P1-2 实施）
**位置**：`src/main/java/com/minion/core/tools/ToolRegistry.java:59-74`；`SubAgentLoop.java:344-353`
**现状**：每次 `schemas()` 为每个工具新建一整棵 `JsonObject`；子代理每次请求（含重试）还要再过滤一遍。
**建议 / 验收**：见 P1-2 —— 缓存 + 注册/门控变更时失效，断言单轮内 schema 构建次数 = 1。

### P2-15 JSON 配置存储：固定 tmp 名、无 ATOMIC_MOVE、解析失败即重置默认值
**位置**：`src/main/java/com/minion/core/tools/plugin/ToolStore.java:53-58,103-110`；`src/main/java/com/minion/core/mcp/McpStore.java:18,44,53`
**现状**：`Files.write(tmp,...)` + `Files.move(tmp, file, REPLACE_EXISTING)`——tmp 名固定为 `tools.json.tmp`（并发保存互踩）、无 `ATOMIC_MOVE`、无 `force`；任何解析异常都被当作"文件损坏"，备份后**直接重写默认值**（数据源与 SSH 凭据全丢，`.bak` 只保留一代）；`Root` 没有 `version` 字段，无法做 schema 迁移；写失败只 `System.err`，UI 仍显示"已保存"。`McpStore.servers` 是普通 `ArrayList` 且 `list()` 直接返回内部引用——设置页在 FX 线程增删、连接线程同时遍历。
**影响**：断电或并发保存 → 配置截断 → 下次启动凭据全丢；"设置看起来生效、重启还原"且无提示；mcp.json 并发读写可能丢服务器或抛 CME。
**建议**：单写者 + 带时间戳的 tmp 名 + `ATOMIC_MOVE` + `FileChannel.force(true)`；解析失败时**只读回退**、不覆盖原文件；保留多代带时间戳备份；加 `version` + 显式迁移；保存失败弹 UI 错误；`McpStore` 改 `CopyOnWriteArrayList` + 对外快照。
**工作量 / 风险**：M / 中（涉及数据迁移）。
**验收**：并发保存 50 次后文件仍完整；注入半截 JSON 后启动，原文件与凭据仍在。

### P2-16 注册表/插件语义：同名覆盖、gate 使冲突检测失效、钩子死代码
**位置**：`ToolRegistry.java:34-38,47,59-74`；`ToolPluginManager.java:22,60,99`；`ToolPlugin.java:35`；`SessionManager.java:329-331,353-355`
**现状（五个独立问题）**：
1. `register` 无条件 `tools.put(key, tool)`——插件可注册 `Bash`/`Write` **覆盖内置工具**，而 `isHighRisk` 语义由插件决定，等于绕过确认链；同名时无任何日志。
2. `get()` 对已禁用插件的工具返回 null，而 `registerMcpTools` 用 `get()` 判重名冲突——禁用 db 插件后同名 MCP 工具会占位，重新启用时 db 工具对象已从 map 中永久消失。
3. `ToolRegistry.gate` 字段非 `volatile`；`DbPlugin.enabled()` 读普通 `boolean`（FX 线程写、会话线程读）；`ToolPluginManager.listeners` 是 `ArrayList`（`notifyListeners` 拷贝与 add/remove 并发不安全）。
4. `createTools` 装配无 try/catch：一个插件初始化抛异常 → **新会话无法创建**（不只是该工具消失），且无提示。
5. `ToolPlugin.onConfigChanged()` 全仓无调用点（死钩子），`ToolPluginManager` 也没有 `shutdown()`——CLAUDE.md 里写的 `plugins.shutdown` 并不存在。
**建议**：内置工具名保留、拒绝覆盖（或插件工具名加 `pluginId:` 前缀）；冲突检测改用不经 gate 的 `contains/rawGet`；`gate` 改 `volatile`、`enabled` 改 `volatile boolean`、listeners 改 COW；逐插件 try/catch 并标记失败；`saver` 内广播 `onConfigChanged`，补 `shutdown()` 并接进 `Main` 的退出钩子。
**工作量 / 风险**：M / 中。
**验收**：插件尝试注册 `Bash` 被拒绝并记日志；禁用一个插件后其余插件与内置工具仍可用；退出时 `shutdown` 被调用。

### P2-17 MCP 工具永不确认，description/schema 直接透传
**位置**：`src/main/java/com/minion/core/tools/mcp/McpProxyTool.java:23-24,39`
**现状**：`isHighRisk` 恒 `false`；`description()`/`schema()` 直接返回服务端下发的对象（schema 是共享可变实例）。
**影响**：①MCP 可能是文件写/命令执行类能力，却**无需任何确认**即可运行（内置 Bash 至少还有危险命令链）；②被攻陷或恶意的 MCP 服务器可以在工具描述里塞指令，直接进入系统提示上下文；③共享 schema 被修改会跨请求/跨会话串改。
**建议**：每个服务器加"信任级别"，默认对写类工具 `isHighRisk=true`（或按名称/注解分级）；description 截断并清洗换行；schema 深拷贝 + 体积上限；事件流记录"由哪个 MCP 服务器调用"。
**工作量 / 风险**：M / 中。
**验收**：写类 MCP 工具触发确认卡片；超长描述被截断；修改 schema 不影响其它会话。

### P2-18 SSH 客户端可靠性：无心跳、超时复用、编码写死、SFTP 原地覆盖
**位置**：`src/main/java/com/minion/core/tools/ssh/SshExecutor.java:83-84,110,121-133,148,203,368`；`SftpGetTool.java:40`；`SftpRenameTool.java:35`
**现状**：①从未 `setServerAliveInterval`，NAT/防火墙 5~10 分钟断链后命令被误判失败；②`session.setTimeout(CONNECT_TIMEOUT_MS)`（socket SO_TIMEOUT）与 `session.connect(...)`（连接超时）共用同一个 10s 常量，语义不同；③exec 用 `while (!ch.isClosed()) { Thread.sleep(100); }` 忙轮询，120s 命令轮询 1200 次；④输出硬编码 UTF-8，GBK 老服务器中文变 U+FFFD；⑤stderr 整段拼在 stdout 之后，时序丢失；⑥`ch.get(remote, local)` 直接写目标，下载中断会用半截文件覆盖用户已有文件，且 `SftpGetTool.isHighRisk` 恒 false；`SftpRenameTool` 覆盖远端文件也不确认。
**建议**：心跳 `setServerAliveInterval(30s)` + `setServerAliveCountMax(3)`；socket 超时与连接超时分离；轮询改事件/自适应等待；charset 可按连接配置并回退 GBK；stdout/stderr 分节输出；下载先写 `.part` 再 `ATOMIC_MOVE`，目标存在则确认；rename 前 `lstat(dst)`。
**工作量 / 风险**：M / 中。
**验收**：`tail -f` 类长命令 10 分钟不断链；GBK 主机中文正常；下载中断不破坏已有文件。

---

## 6. P3 —— 工程化与体验细节（可随手分批）

### P3-1 pom 缺构建治理
**位置**：`pom.xml:11-15,40-46,137-142`
**现状**：只固定了 surefire 2.22.2 与 shade 3.4.1；compiler plugin 靠 Maven 默认（3.6.3→3.8.1，3.9→3.11.0）；JavaFX 用 `system` + `${java.home}/lib/ext/jfxrt.jar`；无 enforcer、无 `.mvn/wrapper`；依赖版本散落（okhttp 4.12 写 3 次、flexmark 0.62.2 写 3 次）；无 `dependencyManagement`；无 JaCoCo/静态检查/CI。
**影响**：换机器/换 Maven 行为漂移；JDK11+ 上 `systemPath` 直接不存在，报"找不到 javafx-8.0"；1262 个用例没有覆盖率数字与门禁。
**建议**：显式 `maven-compiler-plugin:3.8.1` + `maven-enforcer-plugin`（`[1.8,1.9)` + Maven 版本）+ 提交 mvnw；版本提到 `<properties>`；加 JaCoCo report（先不 blocking）；至少一条 `mvn -B verify` 的 pre-push 脚本。
**工作量 / 风险**：S / 低。

### P3-2 fat jar 体积治理
**位置**：`pom.xml:143-163`
**现状**：17.7MB / 解压 39MB。其中 oracle 10.34MB、mysql 5.95MB、fasterxml 5.30MB、flexmark 2.55MB、postgresql 2.06MB；28 个 `Messages_*.properties` 共 1.61MB；`META-INF/versions` 164 项；21 份 LICENSE/NOTICE。
**建议**：shade `<filters>` 排除非英文 locale、`META-INF/versions/**`、重复 LICENSE → 预计省 2MB；评估把 `ojdbc8`（默认不启用，占 26%）改为按需加载或独立下载。**不要**开 `minimizeJar`——驱动靠 `Class.forName` 会被裁掉。
**工作量 / 风险**：M / 中（需回归三库连接）。

### P3-3 外部 config 新键永不补齐、写入非原子
**位置**：`src/main/java/com/minion/core/config/Config.java:36-47,79-87,122-143,146-178`
**现状**：外部 `config.properties` 只在**文件不存在**时写一次，之后新增的键永远进不去（实测 `run/config.properties` 只有 7 键，缺 `paths.read.allowOutside`、`agent.emptyOutput.placeholder`）；`set/appendWhitelist` 直接 `Files.write` 覆盖，无临时文件、无 ATOMIC_MOVE、无 `.bak`（而 JSON 侧三者都有）；非法行静默 `continue`。
**影响**：老用户升级后看不到新键，行为与文档默认值不一致且难排查——这恰恰是 CLAUDE.md 规约第 5 条要求避免的；断电/多开并发时配置可被截断，下次启动静默回默认值（白名单丢失、确认开关被重置）。
**建议**：load 时把 resource 中缺失的键追加进外部文件（保留注释 + 版本段）；写入复用 `AtomicFiles` + `.bak`；解析失败时备份并提示；键名收敛为常量并加"resource ↔ getter 一致"单测。
**工作量 / 风险**：S / 低。

### P3-4 `skills.dir` 按进程 CWD 解析
**位置**：`src/main/java/com/minion/Main.java:36`；`src/resource/config.properties:2`；`Boot.java:79-81`
**现状**：`Paths.get(config.skillsDir()).toAbsolutePath()` 依赖 `user.dir`；只有 RELAUNCH 分支 `ProcessBuilder.directory(jarDir)`，`java -jar` 直启路径不设 CWD。
**影响**：`java -jar D:\app\minion.jar` 从别的目录启动时技能目录为空/错位且无提示，与"配置按 jar 同目录解析"的文档承诺不符。
**建议**：相对路径统一按 `Config.jarDir()` 解析（`jarDir.resolve(dir).normalize()`），或在直启分支前置 CWD 校验并提示。
**工作量 / 风险**：S / 低。

### P3-5 JDK 探测含开发机路径、MINION_JAVA 不校验
**位置**：`src/main/java/com/minion/JdkResolver.java:53-66,107-118,136-152`
**现状**：候选首位写死 `D:\javame\jdk1.8`（开发机路径）；`findJdk8` 对 `MINION_JAVA` 只判 `Files.exists`——写目录也会被当 java.exe 透传；`consoleHidden` 自带一套 `boot.console` 解析，与 `Config` 双实现易漂移。
**建议**：删开发机路径，改注册表/`Program Files`/`where java` 扫描；校验 `isRegularFile && name=java.exe` 并回退；键名与默认值收敛为共享常量。
**工作量 / 风险**：S / 低。

### P3-6 relaunch 不校验子进程、无单实例保护
**位置**：`src/main/java/com/minion/Boot.java:70-87`
**现状**：`new ProcessBuilder(cmd).start(); System.exit(0);` —— 子进程是否起来、是否立刻 OOM/类加载失败一概不知；全仓无单实例锁。
**影响**：javaw 路径失效时表现为"双击没反应"（无任何反馈）；多开实例并发写 `workspace.json`/`model.json`/`config.properties`，互相覆盖，会话目录清理存在竞态。
**建议**：`Process` 存活探测（`waitFor` 短超时）+ 失败回退 `errorExit`；单实例文件锁/端口锁，二次启动聚焦已有窗口。
**工作量 / 风险**：M / 中。

### P3-7 测试无超时护栏、25 处 sleep、无 CI/覆盖率
**位置**：全仓 `@Test(timeout` = 0；`BashToolTest.java:140,144,149-150,167,182`；`SshExecutorTest.java:7-8`；`DbPluginTest.java:144-148`；`pom.xml:138-142`
**现状**：1262 用例串行 70s（8 核机器可压缩 3 倍）；无 surefire `parallel`/`forkedProcessTimeoutInSeconds`/`argLine=-Dfile.encoding=UTF-8`（本机平台编码是 GBK）；BashToolTest 固定 sleep 800/1200ms 后断言进程无残留；SSH/DB 用例连 `127.0.0.1:1` 验证快速失败（注释承认防火墙丢包时只能等满超时）；`git log` 已有一次 flaky 修复（a82016e）。
**影响**：AgentLoop 死锁会让 `mvn test` **无限挂起**（无全局超时）；负载高时 sleep 断言假失败。
**建议**：加 `@Rule Timeout(60s)` 或 `forkedProcessTimeoutInSeconds=600`；固定 sleep 改轮询等待；网络/进程用例打 tag 或 `Assume` 可跳过；`parallel=classes, threadCount=4` + `argLine=-Dfile.encoding=UTF-8` + JaCoCo。
**工作量 / 风险**：M / 低。

### P3-8 三个超大测试类重复装置
**位置**：`AgentLoopTest.java` 1,871 行 / 80 用例；`SubAgentLoopTest.java` 1,230 行 / 39 用例；`AgentLoopCompactTest.java` 697 行 / 19 用例
**现状**：三者各自维护 `TemporaryFolder + FakeLlmClient + ToolRegistry + RecordingUi + ConfirmGate` 装置；部分用例在方法内 `new LlmClient(){...}` 重写 `streamChat/cancel`，同一流式脚本逻辑重复。
**建议**：抽 `AgentLoopFixture`（统一装配）+ `ScriptedLlmClient`（turn 队列/取消信号/延迟注入）；测试类按关注点拆分（循环 / 工具 / 压缩 / 子代理），单类 ≤600 行。
**工作量 / 风险**：M / 低。

### P3-9 文档：CONVENTIONS 与代码不符、README 体积失真、specs 无归档
**位置**：`docs/CONVENTIONS.md:7,8,10,20`；`README.md:130`；`docs/superpowers/`（87 specs + 57 plans）
**现状**：CONVENTIONS 仍写已删除的 `cli` 包、说工具在 `Main` 注册（实际在 `SessionManager.newRegistry`）、说"长期选 okhttp 3.x"（实际全线 4.12）；README 声明产物"约 2.75MB → 8MB"，实测 17.7MB，CLAUDE.md 又写"20-22MB"，三处不一致；README 209 行 31.7KB，最长单行 646 字符；144 篇 spec/plan 无归档无索引，最早的可追溯到 3 个月前。
**影响**：新人按 CONVENTIONS 落位会建出已删除的包；体积数字三处矛盾说明依赖变更后未同步；历史计划占仓库 3.2MB 且与现状脱节，易被误当现行设计。
**建议**：CONVENTIONS 按事实重写并与 CLAUDE.md 去重（CLAUDE 留速查、CONVENTIONS 留细节）；README 压到 ~80 行只留上手/配置表，细节移 `docs/USAGE.md`；体积数字由脚本生成或删掉具体数字；specs/plans 按季度移入 `docs/archive/` 并保留 `INDEX.md`（标注：已实现/废弃/被替代）。
**工作量 / 风险**：M / 低。

### P3-10 版本管理边界
**位置**：`.gitignore`；`.superpowers/sdd/.gitignore`（内容仅 `*`）；`tools/`
**现状**：`tools/` 既未跟踪也无 ignore 规则（现存 `tools/mcp-demo/{target,dependency-reduced-pom.xml}` 全被忽略，等于空壳）；`.superpowers/sdd` 下 88 个 md 全部自忽略（`git status --ignored` 可见）；`.claude/worktrees` 有残留旧 worktree 目录；`src/resource/config.properties` 已跟踪却仍列在 `.gitignore`（对我行无效，易误解）；根目录残留 `dependency-reduced-pom.xml`（内容已不完整——transformer 的 implementation 属性丢失）。
**建议**：明确 `tools/` 策略（纳入或删除）；`.superpowers/sdd` 迁 `docs/sdd/` 或整目录加入顶层 ignore；清理残留 worktree 与 `.bak`；shade 加 `<createDependencyReducedPom>false</createDependencyReducedPom>`（本项目不 deploy）。
**工作量 / 风险**：S / 低。

### P3-11 会话列表每 cell 两次全量拷贝 + 每分钟 `setAll`
**位置**：`src/main/java/com/minion/gui/sidebar/SessionListView.java:41-44,64-70,169-193`
**现状**：同一 cell 两次 `new ArrayList<Message>(h.session.messages)`（`lastMessageTs` 与 `lastSummary`）；`refresh()` 用 `getItems().setAll(...)` 触发全部 cell 重建（新建 SVG/Tooltip/绑定），每分钟 + 每次激活/重命名各一次。
**影响**：500 条消息 × 20 个可见 cell = 每次刷新 2 万次元素拷贝；该"拷贝快照"本身也不是安全快照（工作线程同时 append 时可能读到不一致）。
**建议**：`SessionHandle` 上维护 `volatile lastMessageTs/lastSummary`（append 时更新），cell 直接读；`refresh` 改按 id 增量 `set(i, h)`。
**工作量 / 风险**：S / 低。

### P3-12 无边框窗口 8 个透明 Region 抢占点击
**位置**：`src/main/java/com/minion/gui/ResizeHelper.java:76-111`；`MainWindow.java:100-109,390`
**现状**：`frame.getChildren().add(root)` 之后 `ResizeHelper.attach(stage, frame)` 再把 8 个透明 Region 加进同一个 AnchorPane → Region 在**最上层**。顶部 5px 覆在标题栏上（按下变缩放而非拖窗）；右侧 5px 盖住消息区滚动条右半（拖滚动条变缩放）；右上 14×14 角落与关闭按钮重叠。
**建议**：8 个 Region 插到 `frame` 最底层（或在内容区让出 5px 透明边）；顶部条让给标题栏，只保留左右/四角；配合 `pickOnBounds(false)` 精确命中。
**工作量 / 风险**：S / 低。

### P3-13 滚动置底靠双层 `runLater` + `0.999999` 魔法值
**位置**：`src/main/java/com/minion/gui/MainWindow.java:312-336,473-484,489-514`
**现状**：`Platform.runLater(() -> Platform.runLater(() -> { if (v >= 1.0) setVvalue(0.999999); setVvalue(1.0); }))`；截断补偿用 `content.getLayoutBounds().getHeight()`（布局缓存值）。
**影响**：每个新段/每次截断都把置底推迟两帧，连续流式下"贴底"滞后；补偿可能读到旧高度导致视口跳动；`0.999999` 依赖 skin 内部行为，主题/版本变化即失效。
**建议**：一次 `applyCss()+layout()` 后同步置底（或用 `PulseListener`），删除魔法值；补偿改用 `ChatView` 自维护的段高累加值（与 P1-4 共用缓存）。
**工作量 / 风险**：M / 中。

### P3-14 上传图片在 FX 线程读盘 + base64
**位置**：`src/main/java/com/minion/gui/input/InputView.java:876-882`
**现状**：`Files.readAllBytes` + `Base64.getEncoder().encodeToString` 直接在 FX 线程；单图上限 5MB。
**影响**：机械盘/USB 上选图后冻结数百 ms，且 base64 串常驻两份（chip.content + 随后构建的 `ImagePart`）。
**建议**：选图先建占位 chip，读/编码投已有的 `filePool`，完成后 `runLater` 回填；`ImagePart` 直接持 `byte[]` 或延迟编码。
**工作量 / 风险**：S / 低。

### P3-15 临时文件与失败路径
**位置**：`BashTool.java:91-96,181-182`（脚本/探针落系统临时目录，仅 `deleteOnExit` 兜底）；`SessionManager.java:295-302`（`deleteRecursively` 吞掉所有 IOException）；`Config.java:45,76,141,176`（写失败只 stderr）
**现状**：Bash 每条命令在系统 temp 建两个文件，异常路径不删；会话/工作空间删除的文件失败全部静默。
**建议**：脚本/探针改落会话 tmp 目录（生命周期随会话，已有 `SessionTempCleaner` 兜底）；删除失败记日志并统计；配置写失败经 UI 提示。
**工作量 / 风险**：S / 低。

### P3-16 DB/SSH 无并发上限
**位置**：`DbExecutor.java:58`；`SshExecutor.java:297-301`
**现状**："每次新建连接、不落池"是文档化决策，合理；但全仓没有任何并发上限或信号量。多会话并行 + 模型连续调用时，可打满数据库 `max_connections` 或 SSH `MaxStartups`（触发拒绝服务式失败）。SFTP 的"列出 + 下载 + 改名"一次操作要 3 次 SSH 握手。
**建议**：保留不落池的前提下加**信号量**（每数据源 2~4、全局 8）+ 排队超时；SFTP 在一次工具调用内复用同一个 `Session`；记录单查询/单命令延迟基线并在设计文档标注"已知成本"。
**工作量 / 风险**：M / 低。
**验收**：20 个并发查询时排队而非全部失败；SFTP 三连操作只握手一次。

### P3-17 Write 恒按 UTF-8 覆盖、Edit 全局改 CRLF
**位置**：`WriteTool.java:67-68`；`EditTool.java:98-114`
**现状**：`WriteTool` 用 `content.getBytes(StandardCharsets.UTF_8)` 写入，而 `EditTool` 走 `TextFiles` 保留原编码。Read 已把 GBK 文件转码显示，模型原样 Write 回去就会**静默把整个文件改成 UTF-8**。`EditTool` 只要文件里出现一处 `\r\n`，就把全文 `\n` 全部替换成 `\r\n`——混合行尾文件会产生整文件级 diff。
**建议**：覆盖已存在文件时复用 `TextFiles` 探测原编码并按原编码写回，无法判定时在结果里显式提示"已转为 UTF-8"；`EditTool` 只对命中区间恢复原行尾，并保留 BOM；两者共用同一编码/行尾模块。
**工作量 / 风险**：S / 低。
**验收**：Read→Write 往返一个 GBK 文件后编码不变；编辑混合行尾文件只改动命中行。

---

## 7. 不建议做的事（避免过度优化）

| 想法 | 为什么不做 |
|---|---|
| 引 JDBC 连接池 | 只读低频查询、每次新建连接是刻意设计；池化会引入泄漏与跨会话状态，收益极小。需要节流时用**信号量**（P3-16）而不是池 |
| 上真正的 tokenizer（tiktoken 等） | JDK8 无成熟等价物，模型分词器还会变；用 usage 校准 + 比例修正已能到可用精度（P1-3） |
| 改多模块 Maven | 19k 行单模块，拆模块的构建复杂度与 IDE 体验成本高于收益 |
| 替换 JavaFX 8 | 用户环境约束（Win7/JDK8），迁移成本远超收益；可把"迁移评估条件"写进 README 而非现在动手 |
| 重写 `SqlGuard` 为词法器/解析器 | 现实现已覆盖 8 视图且注释完整；重写风险 > 收益。补性质测试即可（P2-12） |
| 用 `minimizeJar` 缩 fat jar | 三个 JDBC 驱动靠 `Class.forName` 加载，会被裁掉（项目已用 ServicesResourceTransformer + 显式 Class.forName 双保险，别破坏它） |
| 全面引入 ORM/DI 框架 | 现有"每会话 new ToolRegistry + 构造注入"已经够清晰；框架会带来 JDK8 兼容与启动开销 |

---

## 8. 建议实施路线

### 第 1 批：稳定性与安全（约 3~4 天，绝大多数 S 级）
`P0-2` `[DONE]` 帧不结束流 → `P0-3` 畸形帧 → `P0-8` 错误通道 → `P0-1` 确认闸门锁 → **`P0-9` 授权作用域** → `P0-6` 补全锁 → `P0-4` MCP 线程 → `P1-8` Timeline 泄漏 → `P1-9` Bash +3s → `P1-11` Read 上限 → `P1-14` 提问超时
> 这一批全部是"小而确定"的改动，不动架构，能立刻消掉崩溃、假死、丢上下文、内存泄漏、权限扩散五类问题。P0-9 优先于其它安全项：它把"一次读取授权"变成"永久全权"，且用户完全无感。

### 第 2 批：安全纵深与性能（约 1~1.5 周）
`P2-6` 日志落盘 → `P0-11` SQL 残余通道 → `P0-10` SSRF → `P1-12` junction 越界 → `P1-15` DB 连接面 → `P1-2` prompt/schema/请求体缓存 → `P1-3` token 估算与校准 → `P1-1` 落盘与图片 → `P0-5`/`P0-7`（stdio 帧、危险命令） → `P2-3` 子代理预算与记账 → `P2-15` 配置存储 → `P3-3`/`P3-4` 配置修复
> 顺序理由：先有日志，后面所有安全与性能改动的验证才有依据；P1-3 的校准数据来自真实 usage，需要日志配合。

### 第 3 批：交互与资源（约 1~2 周）
`P1-4` 流式渲染 → `P1-5` 投递合并 → `P1-6` 缓冲上限 → `P1-7` 懒恢复与共享 OkHttpClient → `P1-10` MCP 状态机 → `P1-13` 写路径三缺口 → `P2-5` 压缩预算 → `P2-4` 工具取消 → `P2-7`/`P2-8` 配置化与重试 → `P2-16`/`P2-17`/`P2-18`（注册表语义、MCP 确认、SSH 可靠性） → `P3-17` 编码一致性
> 这一批要动 GUI 渲染、网络生命周期与文件写入，务必先补 P3-7 的测试护栏（超时 + 并行）。

### 第 4 批：结构重构（按需，建议先出设计文档）
`P2-1` `runUserTurn` 拆分 → `P2-2` 引擎抽取 → `P2-9` `SessionManager` 拆分 → `P2-10` 去重 → `P2-11` 凭据加密 → `P2-12` SQL 性质测试 → 文档与仓库整理
> 按项目规约，这批必须先写 `docs/superpowers/specs/<日期>-<主题>-design.md` 并经确认。

---

## 9. 附：本次审查的实测数据

```
mvn -o -B test            → Tests run: 1262, Failures: 0, Errors: 0, Skipped: 0
                            Total time: 01:10 min
主代码 151 类 / 19,248 行    测试 120 类 / 18,764 行
超过 400 行的主代码类：
   ChatView 943 · InputView 838 · AgentLoop 834 · SessionManager 775
   SettingsDialog 714 · SqlGuard 642 · MainWindow 609 · AskUserQuestionTool 466
   SshExecutor 433
System.out/err 调用：21 处    printStackTrace：0 处    slf4j Logger：0 处（自身代码）
catch(...) { }  空/近似空：39 处
Thread.sleep 出现：10 处（主代码）   @Test(timeout: 0 处
docs markdown：144 篇（specs 87 / plans 57）
fat jar：17,718,382 B（解压 39MB）
```

---

> 本文只做记录，不含状态跟踪。建议按 §8 分批建 issue；第 4 批先出设计文档再动手（符合 `docs/CONVENTIONS.md` 第 6 条"设计先行"）。
