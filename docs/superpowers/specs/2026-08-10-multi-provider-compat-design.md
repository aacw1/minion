# 多供应商模型兼容（deepseek / qwen）— 设计文档

日期：2026-08-10

## 需求

用户希望模型从 DeepSeek 切到千问（Qwen，阿里百炼 DashScope OpenAI 兼容模式）时**仅改 config 即可**，
且千问上也能开启思考（thinking）。

现状：minion 对接 DeepSeek（thinking max）。经排查，客户端实现是标准 OpenAI 兼容协议，
与千问兼容模式协议一致（messages / tools / tool_calls / reasoning_content / SSE / Bearer），
**唯一不兼容点**是思考参数：

| 供应商 | 开启思考参数 | 关闭思考参数 | 备注 |
|---|---|---|---|
| deepseek | `thinking: {type:"enabled"}` + `reasoning_effort` | 不发送（默认关） | DeepSeek 专有参数 |
| qwen | `enable_thinking: true` | `enable_thinking: false` | qwen3 混合模型**默认开思考**，必须显式传 false 才关；`reasoning_effort` 无此概念，忽略 |

另：千问流式默认不返回 `usage`，需请求体带 `stream_options: {include_usage: true}`（OpenAI 标准参数，
DeepSeek 也支持）才准确。否则走现有估算 fallback，压缩阈值判断会偏差。

## 设计决策（已与用户确认：方案 B + B1）

### 1. 配置层：新增 model.provider

config.properties 新增（默认 deepseek，现有用户零迁移）：

    model.provider=deepseek

Config.java 新增 `provider()` getter，默认 "deepseek"。

用户切千问需改 5 项（README 提供示例）：

    model.provider=qwen
    model.url=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
    model.key=sk-xxx
    model.name=qwen3-max        # 选混合模型（qwen3 系列/qwen-plus）；纯思考模型（qwq/-thinking）不可关思考
    model.maxContextTokens=131072   # 千问窗口通常 128k~256k，900000 会超窗 400

### 2. thinking 参数生成：包内纯函数（可单测）

DeepSeekClient 内新增包内可见静态方法：

    static JsonObject thinkingParams(String provider, boolean thinking, String reasoningEffort)

| provider | thinking=true | thinking=false |
|---|---|---|
| deepseek | `{"thinking":{"type":"enabled"},"reasoning_effort":X}` | `null`（现状：不发参数） |
| qwen | `{"enable_thinking":true}` | `{"enable_thinking":false}`（混合模型默认开，必须显式关） |
| 未知 | 回退 deepseek 行为（宽容，不抛错） | — |

`stream_options` 属请求体**顶层字段**，与 thinking 参数分开处理：qwen 时无论 thinking 开关都发送
`{"stream_options":{"include_usage":true}}`；deepseek 不发送（现状零回归）。

### 3. DeepSeekClient / Main 改动

- 构造签名加 `String provider`（Main.java 传入 `config.provider()`）
- `buildRequest`：thinking 分支改为调用 `thinkingParams(...)`；qwen 时追加顶层 `stream_options`
- reasoning_effort 仅 deepseek 发送（qwen 忽略）
- 类名保留（改名牵连 Main/测试/README/架构文档，收益低），javadoc 注明
  "通用 OpenAI 兼容客户端，内置 deepseek/qwen 思考参数适配"

### 4. 不做的

- 不做通用参数模板机制（用户已确认仅内置两家）
- 不改 model.thinking 语义：统一为"minion 期望的思考开关"，由 thinkingParams 翻译成供应商参数
- 不引入新依赖
- 不改默认 model.name / model.maxContextTokens 默认值（外部 config 已有值，用户切换时自行调整）

## 组件改动

| 文件 | 改动 |
|---|---|
| `src/resource/config.properties` | 新增 `model.provider=deepseek` |
| `src/main/java/com/minion/core/config/Config.java` | 新增 `provider()` getter |
| `src/main/java/com/minion/core/llm/DeepSeekClient.java` | 构造加 provider；新增 `thinkingParams(...)` 纯函数；`buildRequest` 调用 + qwen 追加 stream_options；javadoc 更新 |
| `src/main/java/com/minion/Main.java` | 构造 DeepSeekClient 传 `config.provider()` |
| `src/test/java/com/minion/core/llm/DeepSeekClientTest.java` | `newClient` helper 加 provider 参数；新增 qwen 请求体断言；thinkingParams 单测 |
| `README.md` | 新增"模型供应商"小节：切换示例、混合/纯思考模型提示、未知 provider 回退说明 |
| `CLAUDE.md` | 首行"对接 DeepSeek"改为"对接多供应商 LLM（deepseek/qwen，OpenAI 兼容协议）" |
| `docs/ARCHITECTURE.md` | `DeepSeekClient（SSE 流式）`条目补一句"内置 deepseek/qwen 思考参数适配" |

## 测试

- thinkingParams 纯函数单测：
  - deepseek+true → 含 thinking.type=enabled + reasoning_effort
  - deepseek+false → null
  - qwen+true → enable_thinking:true
  - qwen+false → enable_thinking:false
  - 未知 provider → 回退 deepseek 行为
- mockwebserver 请求体断言：
  - qwen+thinking=true：请求体含 enable_thinking:true、顶层 stream_options；不含 thinking/reasoning_effort
  - qwen+thinking=false：含 enable_thinking:false + stream_options
  - deepseek 回归：thinking=true 时请求体与现状逐字段一致（含 thinking/reasoning_effort，不含 stream_options）
- 现有测试全部保持通过，`mvn test` 全绿
- 不做真实千问环境验证（用户确认仅单测覆盖，真实切换后续自行验证）

## 错误处理

- 未知 provider：回退 deepseek 行为，不抛错（宽容设计，README 写明）
- reasoning_effort 为空串：deepseek + thinking=true 时该字段发送空串（与现状一致，不额外处理）
- qwen 请求体错误（如模型名无效）：走现有 LlmException 映射（HTTP 错误码 → 对应类型），不新增
