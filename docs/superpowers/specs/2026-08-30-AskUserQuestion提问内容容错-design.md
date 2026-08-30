# minion AskUserQuestion 提问内容容错 设计

日期：2026-08-30
状态：已实施并验证（方案 ③ 全量落地 + 重试刹车，766 测试全绿；实施记录见文末）

## 背景

用户反馈「有时候模型发起了工具向客户提问，但是没有显示提问内容」。消息区只出现一行
「模型向你提问」，问题正文/选项全部丢失，用户无从回答。

### 线上实证（`run/session/minion/*.json`，探针直调 `ChatView.askQuestionOf` 复现）

| 用例 | 模型实际输出的 arguments | 旧 askQuestionOf 结果 |
|---|---|---|
| A `20260830-121307` msg[42] | 缺 `question` 键；`options` 为**字符串**，内含 3 个选项的 JSON 文本 + 一段泄漏的工具调用参数标记（问题正文被吞在其中） | **length=0**（正文全丢）→ 用户回「第二个问题没显示」 |
| B `20260830-122251` msg[68] | `question`/`header` 正常，但选项写在 `questions` 键且整段数组被序列化成**字符串** | 只剩问题（390 字选项整体丢弃）→ 用户回「提问没有选项，再发一次」 |
| C 同会话 msg[40]/[44]、`20260829-235157` 5 次 | 规范 `question` + `options` 数组 | 正常渲染（8 次扫描 7 次 OK） |

**A 的成因**：模型把 Anthropic 的参数标记（形如「开标签 name=question 闭标签」）泄漏进 JSON 字符串值，
`question` 键整个丢失、问题文本被吞进 `options` 值里，`options` 同时从数组退化为字符串。
**B 的成因**：模型按 Claude Code 习惯把选项写成 `questions` 键，且整个数组被序列化成字符串。
两者都是模型侧格式偏差——**但 minion 没有任何容错，一律静默丢弃**。

> 注：本文档初稿在描述这些标记时，写作过程本身两次触发同类截断（详见「根因的机制补充」），
> 故全文一律用文字描述标记形态，不落完整字面量。

## 现状分析（根因三层）

1. **UI 静默丢内容（直接致因）** — [ChatView.java](src/main/java/com/minion/gui/chat/ChatView.java)
   `askQuestionOf()` 只认 `question`(string) + `options`(array)，其余情形 `catch → return ""`；
   `appendCollapsible` 见空正文只渲染摘要行，[CollapsibleText.java](src/main/java/com/minion/gui/chat/CollapsibleText.java)
   `update()` 中 `hasText=false` 连展开箭头都不画 → 屏幕上只剩「模型向你提问」。
   对照同文件**正常工作样例** `toolCallBody()`：提取失败 `return json` 兜底原始参数——
   AskUserQuestion 恰是全站唯一缺这层兜底的渲染路径。`header` 亦从不显示。
2. **两端文本不同源** — [AskUserQuestionTool.java](src/main/java/com/minion/core/tools/AskUserQuestionTool.java)
   `execute()` 缺 question 时兜底成常量「请提供完成任务所需的信息」，只经 `onAskUserStart` 喂
   InputView 占位提示；消息区走 args 渲染 → 一个空、一个是常量，没有通道把真问题送出去。
3. **流程无校验** — 提不出内容照样挂起等回答（`fut.get()` 无超时），只能靠用户人工回一句
   逼模型重发（实证 A 靠 msg[44] 人工兜底恢复）。违反 CONVENTIONS 规约 3
   「工具错误返回失败 ToolResult 给模型自调」。

次要缺陷（同批修复）：

4. **提问段可能默认折叠** — `COLLAPSE_THRESHOLD=500` 字符，3 个带长 description 的选项
   极易超限（实证 A/B 正文 390/834 字）→ 折叠成一行「模型向你提问」，是「没显示」的第二类形态。
5. **回答显示两遍** — `SessionController.onAskUserDone` 投递 USER_SUPPLEMENT【输入】，
   [AgentLoop.java:582](src/main/java/com/minion/core/agent/AgentLoop.java) 又投递 TOOL_RESULT
   （正文=同一回答）→ 消息区同一段文本出现两次。

## 方案

### 1. core 侧单一真源：`AskUserQuestionTool.normalize(...) → Ask`

规范化任意畸形 arguments 为可展示结构（gui 渲染、工具挂起文案、输入框占位三处共用）：

```java
public static class Ask {
    public final String question;          // 问题文本（null=提不出）
    public final String header;            // 简短标题（可空）
    public final List<Option> options;     // 选项（永不 null，可空）
    public final boolean multiSelect;
    public final String rawText;           // 兜底原文，永不 null
    public boolean isEmpty();              // 问题与选项都提不出
    public String renderText();            // 消息区正文（isEmpty 时回退 rawText）
    public String placeholder();           // 输入框占位短文本（永不空）
}
public static class Option { public final String label, description; }
public static Ask normalize(JsonObject args);
public static Ask normalize(String json);   // 非 JSON 不抛异常，整段作 rawText
```

容错规则（逐级降级）：

- **question**：`question`（string 非空）→ `questions[0].question`（数组，或字符串反解出的数组）
  → 从字符串值里泄漏的参数标记中抽出正文（**配对闭标签优先，未闭合形态兜底**）
  → `header`（仅标题也比空白强）
- **options**：`options` 数组直接用 → `options` 字符串二次 JSON 解析（整段解不动时
  按括号配对抽出内嵌数组子串再解析——实证 A 全靠这步）→ `questions` 键（数组/字符串两形态）
  → 元素 label 缺失退回 `question`/`description`；无 label 却带 `options` 键的对象判定为
  「提问对象」不当选项（否则 questions 内层 options 永远取不到）
- **标记清洗**：所有取值过 `clean()`——剥配对/未闭合残标记、压缩空白、trim
- **multiSelect**：真布尔或字符串 `"true"`

### 2. 工具侧：占位同源 + 提不出内容快速失败

```java
Ask ask = normalize(args);
if (ask.isEmpty()) {
    return ToolResult.error(INVALID_PREFIX + "必须提供非空 question（options 可选），"
            + "无法向用户展示提问内容，请重新发起提问");   // 不挂起，回传让模型自调（规约 3）
}
if (owner) ui.onAskUserStart(ask.placeholder());   // question → 首选项 label → header → 常量
```

`onAskUserStart` 签名不变（仍收 String），但内容从此与消息区同源。挂起槽位/重入共享
回答/中断复位逻辑一律不动。

### 3. 渲染层：委托 normalize、永不空白、header 进摘要

```java
static String askQuestionOf(Object data)  // 委托 normalize(...).renderText()（旧版此处 return ""）
static String askSummaryText(Object data) // 「模型向你提问 · <header>」，header 空则原文案，超 20 字截断
static boolean askExpanded(String body)   // 提问段恒默认展开，仅超 ASK_FORCE_EXPAND_MAX=4000 才折叠
```

`appendCollapsible` 增 `forcedExpanded` 形参重载（4/5 参版本委托它），非提问路径行为不变。

### 4. 回答去重（仅成功态）

```java
static String toolResultBody(String name, String data) {
    if ("AskUserQuestion".equals(name) && data != null && data.startsWith("ok")) return "";
    return toolResultBody(data);
}
```

失败态必须原样显示——失败原因没有任何其他渲染路径。恢复路径无需改动（同走此函数）。

### 5. 恢复路径识别失败输出

`SessionController.replayHistory`：AskUserQuestion 的 TOOL 消息内容以
`AskUserQuestionTool.INVALID_PREFIX` 开头时**不**重演为 USER_SUPPLEMENT（否则历史里的
「用户说过这句话」被伪装成用户回答），只出工具结果行。

## 测试

新增 `AskUserQuestionNormalizeTest` 13 项（实证 A/B 形态最小化、未闭合标签、questions 对象数组、
label 降级、multiSelect、rawText 兜底、非 JSON/null 入口、placeholder 优先级）；
`AskUserQuestionToolTest` 改 1 增 3（空参数快速失败、吞正文仍能挂起且占位取首选项 label、
中断解除挂起单列保留）；`ChatViewAskQuestionOfTest` 12 项；`ChatViewToolBodyTest` 17 项；
`SessionControllerTest` 追加失败输出不重演为回答。

行为变更需同步的旧用例：`malformedJson_returnsEmptyString` → `malformedJson_fallsBackToRawArgsText`
（非法 JSON 回退原文）；`execute_missingQuestion_usesFallbackText` → `execute_emptyArgs_failsFast…`。

## 影响面

- 只增容错、不改 LLM 请求：`schema()` 保持 `question` 必填单参数形态（线上 8 次提问 7 次按规范输出），
  不把 `questions` 写进 schema（避免与 Claude Code 提示词语义冲突）
- 会话落盘格式、`onAskUserStart/Done` 接口签名、挂起/中断/复位逻辑不变
- 无新依赖；JDK 8 兼容；gui→core 为既有依赖方向
- 文档同步：README 提问条目、本文档

## 不做什么

- 不做历史会话 json 的离线修复/重演（新渲染对旧数据即时生效）
- 不在输入框内联渲染选项（消息区已完整展示，跨线程传 options 徒增复杂度——沿用 2026-08-16 结论）
- 不改 AskUserQuestion 的挂起超时策略（无超时是既有设计）

## 实施记录（2026-08-30）

按本设计落地，`mvn package` **766 测试全绿**。实施中新增/修正六处：

1. **未闭合标记也必须救回**（设计初稿遗漏，端到端探针才暴露）：实证 A 的 options 值里开标记有、
   闭标记无（随流式截断丢失），只按配对扫描会提取失败并降级到 `header`——问题正文仍不可见。
   补第二趟扫描（开标记后取到下一尖括号前），配对优先。
2. **去重只限成功态**：初版无条件吞 AskUserQuestion 结果正文，会把失败原因一起吞掉。
   改为 `startsWith("ok")` 才抑制。
3. **内嵌数组抽取** `firstJsonFragment()`：实证 A 的 options 值是「数组文本 + 尾部杂讯」，
   整段解析必失败，需按括号配对（跳过字符串字面量与转义符）抽子串。

收尾复核（question/options 优先级链逐条走查 + 端到端探针）另修两处显示毛边，各补单测：

4. **正文以空行开头**：`renderText()` 选项循环无条件前置换行，问题提不出而选项可展示时
   正文变成「换行 + [1] …」，消息区多一个空行。改为「已累积内容非空才前置换行」，多选提示同理
   （用例 `renderText_optionsWithoutQuestion_noLeadingBlankLine`）。
5. **摘要与正文重复同一句**：只有 header 的畸形提问里，normalize 已把 header 当问题正文，
   `askSummaryText` 又在摘要带一遍 → 摘要「模型向你提问 · 压缩判断」与正文「压缩判断」重复两行。
   改为 header 与最终 question 相同时摘要退回无 header 文案
   （用例 `askSummaryText_headerUsedAsQuestion_notDuplicated`）。
6. **重试刹车**（你追问「为什么以前没有」时核查出的真实风险，详见下「重试防护」）：
   `invalidStreak` + `MAX_INVALID_STRIKES = 2`，连续第 2 次提不出内容改挂起软兜底——
   AgentLoop 主循环无轮次上限，缺此刹车即会话卡死（用例 3 项）。

### 根因的机制补充（调试过程自身两次复现）

本次调试中助手发出的两次 AskUserQuestion 调用同样被截断污染，与实证 A/B 同因：
**参数值里一旦出现工具调用协议的标记字面量，该调用的参数流即被破坏**——轻则 `options`
退化为字符串（实证 B「提问没有选项」），重则后续键值整体丢失（实证 A「第二个问题没显示」）。
故畸形参数并非罕见异常，而是协议标记进入参数值时的必然结果，minion 侧容错属必需而非可选。
连带结论：本项目源码/测试/注释中一律不落完整标记字面量（主代码用 `LT`/`GT`/`SLASH` 常量拼接、
测试用 `TAG_OPEN`/`TAG_CLOSE` 拼接），否则修改这些文件本身就会破坏工具调用。

### 已知限制

- 历史 TOOL 消息不落成败标记，失败/回答的区分依赖 `INVALID_PREFIX` 约定；模型若恰以该前缀
  开头作答会被误判为非回答（概率可忽略，且仅影响恢复视图的【输入】行）。
- 提问失败会占用工具往返；**AgentLoop 主循环无轮次上限**（`while (!interrupted)`，
  SubAgentLoop 注释亦写明无上限），故本工具自带计数刹车兜底（见「重试防护」）。

### 重试防护（已实施）

`AskUserQuestionTool` 自带计数刹车：字段 `invalidStreak`（任一规范提问即归零）+
阈值 `MAX_INVALID_STRIKES = 2`。第 1 次提不出内容仍回传失败让模型自纠（线上实证
msg[42] 畸形 → msg[44] 即发对），连续第 2 次起改挂起软兜底，占位文案说明
「模型未给出可显示的提问内容（参数格式异常）」并把决策权交回用户，彻底消除卡死。
软兜底文案不得以 `INVALID_PREFIX` 开头（否则恢复路径会把它当成失败输出），已加用例守住。

### 验证

- 端到端探针（`run/.session/tmp/…/AskProbe3.java`，直调编译产物 + 线上四份 arguments 原文）：
  `args42`（实证 A）渲染长度 **0 → 337**（问题正文 + 3 选项 + header 摘要 + 占位同源）；
  `argsB`（实证 B）选项 **0 → 4** 全部渲染；`args40`/`args44`（原本正常）输出逐字不变（无回归）。
- 相关测试 75 项 + 全量 766 项通过，`mvn package` BUILD SUCCESS。
