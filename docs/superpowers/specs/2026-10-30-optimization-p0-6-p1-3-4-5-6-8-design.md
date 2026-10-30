# 优化批次设计：P0-6 / P1-3 / P1-4 / P1-5 / P1-6 / P1-8

- 日期：2026-10-30
- 来源：`docs/OPTIMIZATION-BACKLOG.md`（审查基线 `b25cdbf`，1262 用例全绿）
- 范围：6 条「用户可感知」的性能/资源项，全部为**行为等价改造**优先（渲染结果、压缩语义、
  事件顺序不变），只在明确要求处引入新语义（token 校准、发送前硬闸、条目上限）

## 0. 总原则

1. **纯逻辑外提**：所有可测的判定（估算、展平、合并窗口、事件队列、LRU）抽成无 JavaFX 依赖的
   纯类，单测覆盖；UI 侧只做接线。
2. **不改协议语义**：reasoning_content 回传、tool_call↔tool 配对、流式事件顺序全部保持。
3. **上限有据**：每个新增上限都写明理由与降级行为（截断/丢弃最旧/合并），且都在既有
   「截断保活」语义之内。

## 1. P0-6 文件补全锁把全仓库遍历引回 FX 线程

**根因**：`FileSuggester` 全部方法 `synchronized`，`load()` 在监视器内做全量 `walk()`；
FX 线程每次敲键走 `listStaleOk/isFresh` 都要抢同一把锁 → 后台扫描期间输入框冻结。

**改法**：
- `caches` 改 `ConcurrentHashMap`，方法不再 `synchronized`；`walk()` 在锁外完成，完成后 `put`
  不可变 `Entry`（`files = Collections.unmodifiableList(...)`）。
- 读接口返回**同一不可变列表**（不再每次 `new ArrayList` 全量拷贝）；`SuggestionPopup`
  只读遍历，不修改。
- `walk()` 增加条目上限 `MAX_ENTRIES = 20000`（`FileVisitResult.TERMINATE` 提前收敛）：
  十万级仓库不再无限收集 + 每次敲键全量排序；补全本身只需前若干命中。
- `Suggestion` 增加构造期缓存的小写 label，`filter` 比较不再逐次 `toLowerCase()` 分配。

**验收**：`walk` 期间 FX 线程 `listStaleOk/isFresh/listCached` 不被阻塞（并发单测：一边 `load`
大目录、一边高频读，读调用总耗时 < 遍历耗时）；缓存条目不可变；超过上限时结果截断且不抛错。

## 2. P1-3 Token 估算每轮 6~9 次全量扫描且不校准

**根因**：`estimate` 逐字符全量扫描；压缩检查点 `shouldCompress → worthCompressing →
（chunkGroups + keepCount + 逐组 estimate）→ estimate 百分比 → shouldCompress` 共 4 次全量；
估算从不与真实 usage 校准；`isCjk` 只覆盖 4 个区段（假名/韩文/emoji 严重低估）；发送前无硬闸。

**改法**：
- `TokenCounter`：按 **code point** 遍历（代理对不再按 2 个 ASCII 计）；CJK 判定补 Hiragana/
  Katakana/Hangul（Jamo/兼容/音节）/CJK Ext-B/兼容形式/全角；emoji 按 1.5 token/个。
  BMP 汉字与 ASCII 权重不变（既有 token 断言零影响）。
- `ContextManager.evaluate(messages)`：**单次**生成 `Evaluation{used, max, groups, keep, take,
  compressible, shouldCompress, worthCompressing, forceZone, hardLimit, percent}`；原子组 token
  在切组时一次累加（不再 `chunkGroups` + 逐组重扫）。`shouldCompress/worthCompressing/
  compressibleTokens/estimate` 保留为兼容入口（内部委托 evaluate）。
- **usage 校准**：`calibrate(actualInputTokens, calibratedEstimate)` 由 AgentLoop 在
  `usage.record` 后调用；修正系数 = 真实 / 还原后的原始估算，clamp 0.8~1.5，EMA 平滑 0.5；
  usage 明显不可信（< 200 token、比值越界 0.5~3.0）时忽略，避免把测试桩/网关缺省值当真。
- **发送前硬闸**：`used ≥ 95%×max` 且本轮**无法通过压缩自救**（无可压组 / 收益不足）时
  在发请求前中止并提示（新建会话或 `/compact`），不再把必然 400 的请求发出去。
  已经压缩成功（含压后仍高）的既有语义保持不变（不阻塞本轮）。
- 主/子循环检查点改为「一轮一次 evaluate」，压后复评一次取百分比。

**验收**：日文/emoji 估算误差 < 30%（对 CJK 权重口径）；`evaluate` 与旧四接口结果一致；
校准后 `estimate` 随真实 usage 向真实值收敛；构造「常驻内容占满窗口」的会话，发送前被拦截
且不发请求。

## 3. P1-4 流式渲染全文重算

**根因**：每次合并窗口 flush 对累计全文做 flexmark `toPlainText`（O(n) 解析）→ `setText`
全文重排 → `relayout` 全文测量 → 双 `runLater` 里 `lookupAll(".text")` 精校。总量 O(n²)。

**改法**：
- 新增 `StreamingPlainText`（纯逻辑）：按**行**增量展平，已完成的行只处理一次并缓存结果，
  每帧只处理新增行 + 未完成的最后一行；围栏代码块/标题/引用/列表/表格/行内标记按
  `MarkdownRenderer.toPlainText` 的块间规则近似（最终定稿走一次精确 `toPlainText`，
  **定稿文本与旧实现逐字一致**）。
- 段定稿点（轮次边界 / STATS / SUB_AGENT_DONE）对仍在缓冲的正文做一次精确转换。
- `StreamFlushPolicy` 增加按累计字符自适应窗口：<20k 80ms、20k~50k 150ms、≥50k 250ms
  （旧签名保留 80ms，既有单测不受影响）。
- `MessageTextArea`：流式路径跳过「布局后双 runLater 精校」（只保留构造/定稿精校），
  消除每帧 `lookupAll(".text")` + 二次全量测量。

**验收**：流式期间不再逐帧 flexmark 解析（新增单测断言 `render` 增量语义）；定稿文本与
`MarkdownRenderer.toPlainText(全文)` 完全一致；窗口随长度放大（单测）。

## 4. P1-5 每个 chunk 一个 runLater；状态事件重复投递

**根因**：`ChatView.fxListener` 逐事件 `Platform.runLater`；MainWindow 对同一 running 变化
投递两次 runLater，InputView 内部再套一次。

**改法**：
- 新增 `CoalescingQueue<T>`（纯逻辑）：后台线程只 `offer` 入队，`compareAndSet` 保证同一时刻
  至多一个「排空任务」；FX 线程一次排空整批按序渲染。事件顺序由队列 FIFO + 单排空者保证。
- `MainWindow.onSessionRunningChanged` 合并为**一个** runLater（页签 + 输入区 + 指示器一起刷）；
  `InputView.onRunningChanged` 去掉内部 runLater（调用方保证 FX 线程）。

**验收**：N 个事件只产生 ≤ 队列循环次数个 FX 任务（单测：100 次 offer 只 1 次调度）；
事件顺序不变；running 变化的 runLater 数由 3 降到 1。

## 5. P1-6 EventList 无上限 + 重放锁内拷贝；viewCache 无上限

**根因**：`events` 只增不删；`rebind` 锁内全量拷贝；`viewCache` 只增不淘汰。

**改法**：
- `EventList` 增加容量上限 `MAX_EVENTS = 5000`，超限从头部丢弃并把丢弃数记入 `base`；
  视图游标改为**绝对序号**（`rebind` 内 `max(from, base)` 钳制），保证丢弃后不重放、不丢新事件。
  锁内拷贝因此封顶 5000 个引用（毫秒级），不再随会话时长增长。
  （不采用「合并增量」：合并会让绝对序号与已渲染游标错位，切回视图可能重复或丢内容；
  视图侧已有 200 段截断保活，丢最旧事件的语义与之一致。）
- `ViewCache<V>`（纯逻辑 LRU，上限 4）：淘汰时回调解绑（`bind(false)`），删除/切空间
  同样回调；淘汰视图下次激活从 EventList 重建并按 `base` 重放。

**验收**：连续 2 小时会话事件数稳定 ≤ 5000；丢弃后 `rebind` 从 `base` 起重放、不重复；
LRU 上限生效且淘汰视图被解绑。

## 6. P1-8 页签状态点 Timeline 泄漏

**根因**：`updateTab` 每次 `t.setGraphic(StatusDot.create(running))` 新建 Circle，旧 Circle 上的
`INDEFINITE Timeline` 从不 stop（`StatusDot` 类注释已写明必须 stopPulseIn）；Tooltip 也每次新建。

**改法**：每个页签在 `Tab.getProperties()` 里复用一个 `Circle` 与一个 `Tooltip`：
running 变化只 `startPulse/stopPulse`，标题只 `tooltip.setText`；页签关闭时 `stopPulse`
（节点离开场景图前回收动画）。

**验收**：20 回合后 Circle 实例数不增长（代码路径复用同一节点，无新建）；关闭页签后无动画残留。

## 7. 测试与文档

- 新增/更新单测：`FileSuggesterTest`（上限/不可变/并发读不被遍历阻塞）、`TokenCounterTest`
  （假名/韩文/emoji/代理对）、`ContextManagerTest`（evaluate 与分组一致性 + 校准 + 硬闸）、
  `AgentLoopCompactTest` 新增（硬闸拦下请求/校准回灌）、`StreamingPlainTextTest`、
  `ChatViewFlushPolicyTest`（自适应窗口）、`CoalescingQueueTest`、`EventListTest`（上限/游标
  钳制）、`ViewCacheTest`。
- 文档：本设计 + README/ARCHITECTURE 相关段落补充（常量与上限）。

### 7.1 实测（2026-10-30，本机 JDK 1.8.0_181，`target` 下一次性压测，不入库）

| 场景 | 旧实现 | 新实现 |
|---|---|---|
| 10 万字符 markdown，按每 2k 字符一帧共 50 帧：展平总耗时 | 891ms（每帧全文 flexmark 解析） | 74ms（增量展平，最慢单帧 4ms） |
| 10 万字符定稿：增量展平结果 vs `toPlainText(全文)` | — | **逐字一致** |
| 2 万个补全条目：`SuggestionPopup.filter`（空查询全排序，FX 线程） | 逐条 `toLowerCase` 分配 | 最坏 4ms（`lower` 构造期缓存后） |
| 全量测试 | 1262 用例 | **1314 用例全绿**（新增 52 条），`mvn -o test` 约 61s |

