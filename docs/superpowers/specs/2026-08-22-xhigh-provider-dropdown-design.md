# 思考强度 xhigh 与 provider 下拉化设计

日期：2026-08-22
状态：待实施
影响范围：SettingsDialog（表单）、DeepSeekClient（报文生成）、ModelManager（默认值/兜底）、测试

## 背景与需求

1. 模型思考强度（reasoning_effort）增加 `xhigh` 选项，兼容千问。
2. provider 由自由文本框改为下拉选项（qwen / deepseek）。

经千问官方文档（platform.qianwenai.com OpenAI Chat API）确认：

- 千问平台支持 `reasoning_effort` 字段（OpenAI 兼容标准参数），枚举含 `low/medium/high/xhigh/max`；
  Qwen3 系列实际档位 `low/medium/xhigh`（映射 thinking_budget 4096/16384/262144），平台默认 `xhigh`。
- DeepSeek-V4 系列支持 `high/max`（low/medium 映射为 high，xhigh 映射为 max）。
- 思考开关入参两者不同：qwen 用 `enable_thinking`（非标准参数，qwen3 混合模型默认开思考，
  关闭必须显式传 `false`）；deepseek 用 `thinking: {type:"enabled"}`。故 provider 必须保留，
  用于生成各自的思考开关入参，报文无法完全统一。

## 设计

### 1. 设置表单（SettingsDialog.form）

- effort 下拉选项：`low / medium / high / xhigh / max`（新增 xhigh）；新建表单默认 `max`（用户可改，模板默认值由 createQwen/createDeepseek 决定）
- provider 文本框 → 下拉 `ComboBox<String>`：`qwen` / `deepseek`（新建默认 deepseek）；编辑已有配置时按原值忽略大小写匹配回填（匹配不到则取 deepseek）
- 「深度思考」CheckBox **保留**（不删除）

### 2. 配置（ModelConfig / ModelManager）

- `ModelConfig.thinking`、`reasoningEffort`、`provider` 字段均保留，无结构变化。
- 默认值调整：`createQwen` 的 reasoningEffort 由 `max` 改为 `xhigh`（千问平台默认/最高档）；
  `createDeepseek` 保持 `max`。
- 兜底归一化：`ModelManager.load` 过滤循环中，若 `reasoningEffort` 为 null/空，
  按 provider 归一化（qwen→`xhigh`，其余→`max`），避免请求体发出 null 值。

### 3. 请求报文（DeepSeekClient.thinkingParams）

| provider | thinking=true | thinking=false |
|---|---|---|
| qwen | `enable_thinking: true` + `reasoning_effort: <effort>` | `enable_thinking: false`（仅此，不发 effort） |
| deepseek / 未知 | `thinking: {type:"enabled"}` + `reasoning_effort: <effort>` | 不发任何思考参数 |

- qwen 分支现状（仅 enable_thinking）扩展：thinking=true 时追加 `reasoning_effort`。
- `stream_options.include_usage` 逻辑不变（仅 qwen 发送）。
- thinking 参数 `DeepSeekClient` 构造签名不变（仍含 thinking/reasoningEffort/provider）。

### 4. 兼容性

- 旧 model.json 无需迁移：thinking 缺失视为 false（用户勾选开启后按供应商拼接入参）；
  reasoningEffort 缺失由 load 兜底归一化；provider 缺失/未知值走 deepseek 分支（现状行为）。
- 未知 provider 值在下拉中不显示（编辑旧配置时下拉取 qwen/deepseek 就近值，保存后落库）。

### 5. 测试

- `DeepSeekClientTest`：
  - qwen + thinking=true：断言报文体含 `enable_thinking:true` 与 `reasoning_effort:<effort>`
  - qwen + thinking=false：断言仅 `enable_thinking:false`，无 reasoning_effort
  - deepseek + thinking=true + xhigh：断言 `thinking:{type:"enabled"}` 与 `reasoning_effort:xhigh`
  - 未知 provider 仍走 deepseek 分支（既有用例保持）
- `ModelManagerTest`：createQwen 默认 reasoningEffort=xhigh；load 兜底归一化用例。

## 不做的事（YAGNI）

- 不按 provider 联动 URL / 模型名默认值
- provider 下拉不做可编辑（自定义 provider 值不支持 UI 编辑）
- 不引入 thinking_budget 参数（千问与 effort 互斥，effort 已覆盖需求）
