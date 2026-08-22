# 思考强度 xhigh 与 provider 下拉化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** effort 下拉增加 xhigh 且 qwen 请求体发送 reasoning_effort；provider 由文本框改为 qwen/deepseek 下拉。

**Architecture:** 三处独立改动：DeepSeekClient.thinkingParams（报文生成纯函数）、ModelManager（默认值与兜底）、SettingsDialog（表单控件）。构造签名均不变，无新依赖。

**Tech Stack:** JDK 8、JavaFX 8、gson、junit4、mockwebserver

## Global Constraints

- JDK 8 兼容；不新增依赖
- 报文契约：qwen 思考开启 = `enable_thinking:true` + `reasoning_effort`；deepseek 思考开启 = `thinking:{type:"enabled"}` + `reasoning_effort`；qwen 关闭 = 仅 `enable_thinking:false`；deepseek 关闭 = 不发思考参数
- 千问 effort 档位最高 xhigh；deepseek 最高 max（xhigh 透传由服务端映射）
- createQwen 默认 effort = `xhigh`；createDeepseek 保持 `max`
- 所有 commit 用中文 conventional 格式

---

### Task 1: DeepSeekClient 报文 —— qwen 思考开启时发送 reasoning_effort

**Files:**
- Modify: `src/main/java/com/minion/core/llm/DeepSeekClient.java:84-100`（thinkingParams 方法体）
- Test: `src/test/java/com/minion/core/llm/DeepSeekClientTest.java`（thinkingParams_qwenEnabled、request_qwenEnabled_sendsEnableThinkingAndStreamOptions；新增 xhigh 用例）

**Interfaces:**
- Consumes: 现有 `thinkingParams(String provider, boolean thinking, String reasoningEffort)` 签名（不变）
- Produces: 同签名；qwen+thinking=true 时返回 JSON 含 `enable_thinking:true` 与 `reasoning_effort:<effort>`

- [ ] **Step 1: 更新现有失败测试（qwen 开启时应带 effort）**

在 `DeepSeekClientTest.java` 修改 `thinkingParams_qwenEnabled`，将最后两行断言改为：

```java
    @Test
    public void thinkingParams_qwenEnabled() {
        JsonObject p = DeepSeekClient.thinkingParams("qwen", true, "max");
        assertNotNull(p);
        assertTrue(p.get("enable_thinking").getAsBoolean());
        assertFalse(p.has("thinking"));
        assertEquals("max", p.get("reasoning_effort").getAsString());
    }
```

新增 xhigh 用例（紧跟其后）：

```java
    @Test
    public void thinkingParams_qwenEnabled_xhigh() {
        JsonObject p = DeepSeekClient.thinkingParams("qwen", true, "xhigh");
        assertNotNull(p);
        assertTrue(p.get("enable_thinking").getAsBoolean());
        assertEquals("xhigh", p.get("reasoning_effort").getAsString());
    }
```

修改 `request_qwenEnabled_sendsEnableThinkingAndStreamOptions`，断言 `assertFalse(json.has("reasoning_effort"))` 改为：

```java
        assertEquals("max", json.get("reasoning_effort").getAsString());
```

- [ ] **Step 2: 运行测试确认失败**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn test -Dtest=DeepSeekClientTest`
Expected: FAIL（reasoning_effort 断言失败，qwen 分支未发该字段）

- [ ] **Step 3: 修改 thinkingParams 实现**

`src/main/java/com/minion/core/llm/DeepSeekClient.java` 中 `thinkingParams` 的 qwen 分支改为：

```java
        if ("qwen".equalsIgnoreCase(provider)) {
            JsonObject o = new JsonObject();
            o.addProperty("enable_thinking", thinking);
            if (thinking) {
                o.addProperty("reasoning_effort", reasoningEffort);
            }
            return o;
        }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn test -Dtest=DeepSeekClientTest`
Expected: PASS（含新增 xhigh 用例；deepseek/未知 provider/关闭思考用例不受影响）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/llm/DeepSeekClient.java src/test/java/com/minion/core/llm/DeepSeekClientTest.java
git commit -m "feat(llm): qwen 思考开启时发送 reasoning_effort（含 xhigh 档）"
```

---

### Task 2: ModelManager —— createQwen 默认 xhigh + load 兜底归一化

**Files:**
- Modify: `src/main/java/com/minion/core/config/ModelManager.java:81`（createQwen 默认值）、`:57-60`（load 过滤循环加兜底）
- Test: `src/test/java/com/minion/core/config/ModelManagerTest.java`

**Interfaces:**
- Consumes: `ModelConfig.reasoningEffort`（String，可空）
- Produces: `ModelManager.load` 保证返回的所有模型 reasoningEffort 非空（qwen→xhigh，其余→max）

- [ ] **Step 1: 写失败测试**

在 `ModelManagerTest.java` 的 `load_createsDefaultModels` 中 qwen 断言区追加：

```java
        assertEquals("xhigh", q.reasoningEffort);
```

并确认 deepseek 断言区追加：

```java
        assertEquals("max", c.reasoningEffort);
```

新增兜底用例（文件尾、save_atomicWrite 用例前）：

```java
    /** 旧 model.json 缺 reasoningEffort：按 provider 归一化（qwen→xhigh，其余→max） */
    @Test
    public void load_missingReasoningEffortNormalized() throws IOException {
        Path dir = jarDir();
        String json = "{\"models\":["
                + "{\"displayName\":\"q\",\"url\":\"http://q\",\"modelName\":\"qwen3-max\",\"provider\":\"qwen\",\"maxContextTokens\":131072},"
                + "{\"displayName\":\"d\",\"url\":\"http://d\",\"modelName\":\"deepseek-v4-flash\",\"provider\":\"deepseek\",\"maxContextTokens\":900000}"
                + "],\"currentModelName\":\"q\"}";
        Files.write(dir.resolve("model.json"), json.getBytes(StandardCharsets.UTF_8));
        ModelManager m = ModelManager.load(dir);
        assertEquals("xhigh", m.get("q").reasoningEffort);
        assertEquals("max", m.get("d").reasoningEffort);
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn test -Dtest=ModelManagerTest`
Expected: FAIL（createQwen 仍为 max；load 兜底未实现）

- [ ] **Step 3: 实现**

`ModelManager.createQwen`：`c.reasoningEffort = "max";` 改为 `c.reasoningEffort = "xhigh";`（并同步更新方法上方注释：删除"reasoningEffort 对 qwen 无效"字样，改为千问支持 effort 至 xhigh）。

`ModelManager.load` 过滤循环（`if (c != null && c.displayName != null && !c.displayName.trim().isEmpty())` 块内，`m.models.add(c)` 之前）插入：

```java
                        // reasoningEffort 缺失兜底：qwen 默认 xhigh（平台档位最高），其余默认 max
                        if (c.reasoningEffort == null || c.reasoningEffort.trim().isEmpty()) {
                            c.reasoningEffort = "qwen".equalsIgnoreCase(c.provider) ? "xhigh" : "max";
                        }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn test -Dtest=ModelManagerTest`
Expected: PASS（load_validExistingFileUntouched 无 reasoningEffort 字段、provider=deepseek → 归一化 max，不受影响）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/config/ModelManager.java src/test/java/com/minion/core/config/ModelManagerTest.java
git commit -m "feat(config): createQwen 默认 effort=xhigh，load 缺失 effort 按 provider 归一化"
```

---

### Task 3: SettingsDialog —— effort 加 xhigh、provider 下拉化

**Files:**
- Modify: `src/main/java/com/minion/gui/dialog/SettingsDialog.java`（form 方法，约 193-235 行）

**Interfaces:**
- Consumes: `ModelConfig.provider`（String：qwen/deepseek）
- Produces: 表单返回的 ModelConfig.provider 仅 qwen/deepseek；effort 可选 low/medium/high/xhigh/max

- [ ] **Step 1: 修改表单控件**

`form(ModelConfig mc)` 中：

1. provider 文本框改为下拉（替换 `TextField provider = new TextField(...)` 行）：

```java
        ComboBox<String> provider = new ComboBox<String>();
        provider.getItems().addAll("qwen", "deepseek");
        if (mc == null) {
            provider.setValue("deepseek");
        } else {
            // 编辑旧配置：按原值忽略大小写回填，匹配不到取 deepseek
            String p = mc.provider == null ? "deepseek" : mc.provider;
            boolean hit = false;
            for (String opt : provider.getItems()) {
                if (opt.equalsIgnoreCase(p)) { provider.setValue(opt); hit = true; break; }
            }
            if (!hit) provider.setValue("deepseek");
        }
```

2. effort 下拉选项追加 xhigh（`effort.getItems().addAll("low", "medium", "high", "max");` 改为）：

```java
        effort.getItems().addAll("low", "medium", "high", "xhigh", "max");
```

3. resultConverter 中 provider 取值改为（原 `out.provider = provider.getText().trim();`）：

```java
            out.provider = provider.getValue() == null ? "deepseek" : provider.getValue();
```

- [ ] **Step 2: 编译验证**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn compile`
Expected: 编译通过（无未用变量告警即可，TextField provider 已被替换）

- [ ] **Step 3: 运行全部测试确认无回归**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn test`
Expected: 全部 PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/minion/gui/dialog/SettingsDialog.java
git commit -m "feat(gui): 模型表单 effort 增加 xhigh，provider 改为 qwen/deepseek 下拉"
```

---

## 自审记录

- **Spec 覆盖**：xhigh 选项（T1/T3）、qwen 发 effort（T1）、provider 下拉化（T3）、createQwen 默认 xhigh（T2）、load 兜底（T2）、测试更新（T1/T2）；「深度思考」开关保留、报文差异表全部落实。
- **无占位符**：所有步骤含完整代码。
- **类型一致**：thinkingParams 签名不变；ModelConfig 字段不变；SettingsDialog 表单变量名沿用。
