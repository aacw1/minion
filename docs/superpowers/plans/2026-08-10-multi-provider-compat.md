# 多供应商模型兼容（deepseek / qwen）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 `model.provider` 配置，使 minion 可在 deepseek / qwen 之间纯 config 切换，且 qwen 下思考参数（enable_thinking）与 usage 统计（stream_options）正确工作。

**Architecture:** 配置层新增 `model.provider=deepseek`（默认）；DeepSeekClient 构造加 provider 参数，请求体 thinking 分支改为调用新增纯函数 `thinkingParams(provider, thinking, reasoningEffort)` 生成供应商参数（deepseek: thinking/reasoning_effort；qwen: enable_thinking），qwen 时另在请求体顶层追加 `stream_options: {include_usage: true}`。

**Tech Stack:** JDK 8、Gson、OkHttp 3、JUnit 4、MockWebServer

## Global Constraints

- JDK 8 兼容，无新依赖
- 文档、注释、commit 用中文；commit 用 conventional 格式，结尾附 `Co-Authored-By: Claude <noreply@anthropic.com>`
- 测试 junit4 + mockwebserver；请求体断言用 `JsonParser.parseString(...).getAsJsonObject()`
- API 契约不破坏：reasoning_content 原样回传；tool_call↔tool 消息完整配对
- DeepSeek（provider=deepseek）请求体行为零回归：thinking=true 时含 `thinking`/`reasoning_effort`，不含 `stream_options`
- 设计文档：`docs/superpowers/specs/2026-08-10-multi-provider-compat-design.md`（已提交 912e567）

---

### Task 1: thinkingParams 纯函数（TDD）

**Files:**
- Modify: `src/main/java/com/minion/core/llm/DeepSeekClient.java`（新增包内 static 方法，加在 `cancel()` 之前）
- Test: `src/test/java/com/minion/core/llm/DeepSeekClientTest.java`

**Interfaces:**
- Consumes: 无（Task 1 不依赖其他任务）
- Produces: `DeepSeekClient.thinkingParams(String provider, boolean thinking, String reasoningEffort)` → `JsonObject` 或 `null`。deepseek/未知 + true → `{"thinking":{"type":"enabled"},"reasoning_effort":X}`；deepseek/未知 + false → null；qwen → 恒返回 `{"enable_thinking":bool}`（不为 null，qwen3 混合模型默认开思考，必须显式关）。Task 2 的 `buildRequest` 依赖此签名。

- [ ] **Step 1: 写失败测试**

在 `DeepSeekClientTest.java` 的类体末尾（`}` 前）、`streamChat_emptyChoicesChunkStillParsesUsage` 测试之后新增：

```java
    // ===== 多供应商：thinkingParams 纯函数 =====

    @Test
    public void thinkingParams_deepseekEnabled() {
        JsonObject p = DeepSeekClient.thinkingParams("deepseek", true, "max");
        assertNotNull(p);
        assertEquals("enabled", p.getAsJsonObject("thinking").get("type").getAsString());
        assertEquals("max", p.get("reasoning_effort").getAsString());
    }

    @Test
    public void thinkingParams_deepseekDisabled_returnsNull() {
        assertNull(DeepSeekClient.thinkingParams("deepseek", false, "max"));
    }

    @Test
    public void thinkingParams_qwenEnabled() {
        JsonObject p = DeepSeekClient.thinkingParams("qwen", true, "max");
        assertNotNull(p);
        assertTrue(p.get("enable_thinking").getAsBoolean());
        assertFalse(p.has("thinking"));
        assertFalse(p.has("reasoning_effort"));
    }

    @Test
    public void thinkingParams_qwenDisabled() {
        JsonObject p = DeepSeekClient.thinkingParams("qwen", false, "max");
        assertNotNull(p);
        assertFalse(p.get("enable_thinking").getAsBoolean());
    }

    @Test
    public void thinkingParams_unknownProvider_fallsBackToDeepseek() {
        JsonObject p = DeepSeekClient.thinkingParams("other", true, "max");
        assertNotNull(p);
        assertEquals("enabled", p.getAsJsonObject("thinking").get("type").getAsString());
        assertEquals("max", p.get("reasoning_effort").getAsString());
    }
```

- [ ] **Step 2: 跑测试确认失败（编译错误）**

Run: `mvn test -Dtest=DeepSeekClientTest`
Expected: FAIL —— 编译错误 `cannot find symbol: method thinkingParams(...)`

- [ ] **Step 3: 实现 thinkingParams**

在 `DeepSeekClient.java` 中 `cancel()` 方法之前新增（并在 `private final String reasoningEffort;` 字段与构造方法之间不需要改动，方法直接放类内）：

```java
    /** 按供应商生成思考参数；deepseek/未知关闭思考时返回 null（不发参数）。
     *  qwen3 混合模型默认开思考，必须显式传 enable_thinking=false 才关，故关闭时也返回参数。 */
    static JsonObject thinkingParams(String provider, boolean thinking, String reasoningEffort) {
        if ("qwen".equals(provider)) {
            JsonObject o = new JsonObject();
            o.addProperty("enable_thinking", thinking);
            return o;
        }
        if (!thinking) return null;
        JsonObject o = new JsonObject();
        JsonObject th = new JsonObject();
        th.addProperty("type", "enabled");
        o.add("thinking", th);
        o.addProperty("reasoning_effort", reasoningEffort);
        return o;
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -Dtest=DeepSeekClientTest`
Expected: PASS —— 5 个新测试全绿，其余 8 个既有测试不受影响

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/core/llm/DeepSeekClient.java src/test/java/com/minion/core/llm/DeepSeekClientTest.java
git commit -m "feat: 新增 thinkingParams 按供应商生成思考参数

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: provider 参数贯通（构造/buildRequest/Config/Main）

**Files:**
- Modify: `src/resource/config.properties`（新增 `model.provider=deepseek`）
- Modify: `src/main/java/com/minion/core/config/Config.java`（新增 `provider()` getter，放 `modelName()` 之后）
- Modify: `src/main/java/com/minion/core/llm/DeepSeekClient.java`（构造 6 参、`provider` 字段、`buildRequest` 改造、javadoc、新增 import）
- Modify: `src/main/java/com/minion/Main.java`（第 46-47 行构造传 `config.provider()`）
- Test: `src/test/java/com/minion/core/llm/DeepSeekClientTest.java`（`newClient` 重载 + 2 个 qwen 请求体测试 + 既有测试加回归断言）

**Interfaces:**
- Consumes: Task 1 的 `thinkingParams(String, boolean, String)`；`Config.provider()`（本任务新增，默认 "deepseek"）
- Produces: `new DeepSeekClient(url, apiKey, model, thinking, reasoningEffort, provider)` 6 参构造；qwen 时请求体含顶层 `stream_options:{include_usage:true}`；deepseek 时不含 stream_options

- [ ] **Step 1: Config 与 config.properties 加 provider**

`src/resource/config.properties` 的 `# ===== 模型 =====` 段，`model.url` 行之前插入：

```properties
model.provider=deepseek
```

`src/main/java/com/minion/core/config/Config.java` 中 `modelName()` 方法之后新增：

```java
    public String provider()     { return get("model.provider", "deepseek"); }
```

- [ ] **Step 2: 更新测试 helper 并新增 qwen 请求体测试（预期编译失败）**

`DeepSeekClientTest.java` 中把 `newClient()` 替换为两个重载：

```java
    private DeepSeekClient newClient() {
        return newClient("deepseek", true, "max");
    }

    private DeepSeekClient newClient(String provider, boolean thinking, String reasoningEffort) {
        return new DeepSeekClient(server.url("/").toString(),
                "sk-test", "deepseek-v4-flash", thinking, reasoningEffort, provider);
    }
```

在 `streamChat_parsesDeltasAndFinish` 测试中，请求体断言处（`assertEquals("enabled", ...)` 行之后）追加一行回归断言：

```java
        assertFalse(json.has("stream_options")); // deepseek 零回归：不发送 stream_options
```

在类体末尾新增两个测试：

```java
    // ===== 多供应商：qwen 请求体 =====

    @Test
    public void request_qwenEnabled_sendsEnableThinkingAndStreamOptions() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n", 1));
        newClient("qwen", true, "max").streamChat(
                Collections.<Message>singletonList(Message.user("hi")), null,
                new StreamHandler() {
                    @Override
                    public void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls) { }
                });
        RecordedRequest req = server.takeRequest();
        JsonObject json = JsonParser.parseString(req.getBody().readUtf8()).getAsJsonObject();
        assertTrue(json.get("enable_thinking").getAsBoolean());
        assertFalse(json.has("thinking"));
        assertFalse(json.has("reasoning_effort"));
        assertTrue(json.getAsJsonObject("stream_options").get("include_usage").getAsBoolean());
    }

    @Test
    public void request_qwenDisabled_sendsEnableThinkingFalse() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n", 1));
        newClient("qwen", false, "max").streamChat(
                Collections.<Message>singletonList(Message.user("hi")), null,
                new StreamHandler() {
                    @Override
                    public void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls) { }
                });
        RecordedRequest req = server.takeRequest();
        JsonObject json = JsonParser.parseString(req.getBody().readUtf8()).getAsJsonObject();
        assertFalse(json.get("enable_thinking").getAsBoolean());
        assertTrue(json.getAsJsonObject("stream_options").get("include_usage").getAsBoolean());
    }
```

- [ ] **Step 3: 跑测试确认编译失败**

Run: `mvn test -Dtest=DeepSeekClientTest`
Expected: FAIL —— 编译错误：6 参构造不存在（`newClient(String, boolean, String)` 中调用）

- [ ] **Step 4: 改造 DeepSeekClient**

`DeepSeekClient.java` 改动：

a) 类 javadoc 与新增 import、字段、构造：

```java
/** OpenAI 兼容 Chat Completions 流式客户端（SSE），内置 deepseek/qwen 思考参数适配。 */
```

import 区新增两行（现有 import 是 `com.google.gson.JsonArray/JsonObject/JsonParser`，在其后追加）：

```java
import com.google.gson.JsonElement;
import java.util.Map;
```

字段区（`private final String reasoningEffort;` 之后）新增：

```java
    private final String provider;
```

构造签名与赋值（整体替换原构造方法）：

```java
    public DeepSeekClient(String url, String apiKey, String model,
                          boolean thinking, String reasoningEffort, String provider) {
        this.url = url;
        this.apiKey = apiKey;
        this.model = model;
        this.thinking = thinking;
        this.reasoningEffort = reasoningEffort;
        this.provider = provider;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
                .build();
    }
```

b) `buildRequest` 中替换 thinking 分支（原 `if (thinking) {...}` 整体）：

```java
        // 按供应商生成思考参数（deepseek: thinking/reasoning_effort；qwen: enable_thinking）
        JsonObject tp = thinkingParams(provider, thinking, reasoningEffort);
        if (tp != null) {
            for (Map.Entry<String, JsonElement> e : tp.entrySet()) body.add(e.getKey(), e.getValue());
        }
        // qwen 流式默认不返回 usage，需显式 include_usage；deepseek 不发送（零回归）
        if ("qwen".equals(provider)) {
            JsonObject so = new JsonObject();
            JsonObject include = new JsonObject();
            include.addProperty("include_usage", true);
            so.add("stream_options", include);
            body.add("stream_options", so);
        }
```

- [ ] **Step 5: Main 传入 provider**

`src/main/java/com/minion/Main.java` 第 46-47 行：

```java
        LlmClient llm = new DeepSeekClient(config.modelUrl(), config.modelKey(),
                config.modelName(), config.thinkingEnabled(), config.reasoningEffort(),
                config.provider());
```

- [ ] **Step 6: 跑测试确认全绿**

Run: `mvn test`
Expected: PASS —— 全部测试通过（DeepSeekClientTest 新增 2 个、回归断言 1 行；其他测试类不受影响）

- [ ] **Step 7: 提交**

```bash
git add src/resource/config.properties src/main/java/com/minion/core/config/Config.java src/main/java/com/minion/core/llm/DeepSeekClient.java src/main/java/com/minion/Main.java src/test/java/com/minion/core/llm/DeepSeekClientTest.java
git commit -m "feat: 支持 qwen 供应商切换（enable_thinking/stream_options）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: 文档更新（README / CLAUDE.md / ARCHITECTURE.md）

**Files:**
- Modify: `README.md`（第 1 行简介 + 新增"模型供应商"章节）
- Modify: `CLAUDE.md`（首行）
- Modify: `docs/ARCHITECTURE.md`（第 14 行包结构 + 第 53 行表格）

**Interfaces:**
- Consumes: Task 2 完成的配置项 `model.provider`（deepseek/qwen）
- Produces: 无（纯文档）

- [ ] **Step 1: README 简介行更新**

`README.md` 第 1 行中 `对接大模型（目前是deepseek）` 改为 `对接多供应商大模型（deepseek/qwen，OpenAI 兼容协议）`。

- [ ] **Step 2: README 新增"模型供应商"章节**

在第 77 行 `## Win7 控制台中文乱码说明（2026-08-10 修复）` 之前插入：

```markdown
## 模型供应商配置（deepseek / qwen）

默认对接 deepseek（thinking max）。切千问（阿里百炼 DashScope OpenAI 兼容模式）改 jar 同目录 config.properties 的 5 项：

    model.provider=qwen
    model.url=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
    model.key=sk-你的百炼APIKey
    model.name=qwen3-max        # 选混合模型（qwen3 系列/qwen-plus）；纯思考模型（qwq/-thinking 变体）思考不可关闭
    model.maxContextTokens=131072   # 千问窗口通常 128k~256k；默认 900000 会超窗报 400

说明：

- `model.thinking=true` 时按供应商翻译思考参数：deepseek → `thinking`/`reasoning_effort`；qwen → `enable_thinking`（qwen3 混合模型默认开思考，关闭时同样显式传 `enable_thinking:false`）
- qwen 下请求自动带 `stream_options: {include_usage: true}`（token 统计准确）
- `model.provider` 为未知值时回退 deepseek 行为

```

- [ ] **Step 3: CLAUDE.md 首行更新**

`CLAUDE.md` 第 2 行 `minion：类 Claude Code 的命令行代码开发助手，Java 实现，对接 DeepSeek（thinking max）。` 改为：

```markdown
minion：类 Claude Code 的命令行代码开发助手，Java 实现，对接多供应商 LLM（deepseek/qwen，OpenAI 兼容协议）。
```

- [ ] **Step 4: ARCHITECTURE.md 两处更新**

第 14 行 `├── llm/                DeepSeekClient（SSE 流式）、Message、ToolCall、Usage、UsageTracker` 改为：

```markdown
    ├── llm/                DeepSeekClient（SSE 流式，内置 deepseek/qwen 思考参数适配）、Message、ToolCall、Usage、UsageTracker
```

第 53 行 `| LlmClient / DeepSeekClient | SSE 流式请求；HTTP 连接 30s / 读取 300s（写死常量） |` 改为：

```markdown
| LlmClient / DeepSeekClient | SSE 流式请求（内置 deepseek/qwen 思考参数适配）；HTTP 连接 30s / 读取 300s（写死常量） |
```

- [ ] **Step 5: 提交**

```bash
git add README.md CLAUDE.md docs/ARCHITECTURE.md
git commit -m "docs: 更新多供应商模型配置说明

Co-Authored-By: Claude <noreply@anthropic.com>"
```
