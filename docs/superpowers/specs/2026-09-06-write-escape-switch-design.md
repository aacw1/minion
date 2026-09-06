# 空间外写开关（Write/Edit 越界放行）+ 读逃逸改名 — 设计文档

日期：2026-09-06

## 需求

1. **空间外写开关**：新增配置开关 `paths.write.allowOutside`（默认 false）。开关**关**时
   Write/Edit 越界写入维持现状——直接拒绝、不弹确认；开关**开**时不再直接拒绝，
   放行判断接入高危确认链：会话放行 / 确认跳过（confirm.skip）/ 工具白名单
   任一命中即放行；均未命中则弹框确认（Y=放行本次 / N=拒绝 / W=会话放行）
2. **命名**：两个开关的用户可见命名改为「空间外读」与「空间外写」：
   - 设置窗基础设置页行标签 `读逃逸:` → `空间外读:`；其下新增一行 `空间外写:`
   - config.properties 注释术语同步改为「空间外读 / 空间外写」
   - 仅改**用户可见文案**（UI 标签、配置文件注释、弹框文案）；代码内部方法名
     （`readAllowOutside`）与配置键 `paths.read.allowOutside` 保持不变，存量配置兼容
3. **范围**：仅 Write / Edit 接入新开关。BrowserScreenshot 越界保存**不动**
   （仍走现有 `checkWriteOutside`，受空间外读开关影响——历史遗留，用户确认不改）

## 现状

- `paths.read.allowOutside`（读逃逸）：Read/Grep/Glob 越界读 → 开关开自动放行，
  关弹确认（[ConfirmGate.checkOutside](src/main/java/com/minion/core/tools/confirm/ConfirmGate.java)）
- Write/Edit 越界写：`WriteTool.outsideGuard` / `EditTool` 中 `PathsGuard.errorIfOutside`
  直接返回拒绝，无确认、无开关、不持有 ConfirmGate
- BrowserScreenshot 越界输出：`checkWriteOutside`，自动放行判断复用空间外读开关
- SettingsDialog 基础设置页一行 `读逃逸:`（CheckBox 允许读取工作区外文件）
- 装配：SessionManager.newRegistry 中 Read/Glob/Grep 注入 gate，Write/Edit 未注入

## 设计决策（已与用户确认）

### 改动 1：配置键与 Config

[Config.java](src/main/java/com/minion/core/config/Config.java) 新增：

```java
/** 空间外写：true 时 Write/Edit 越界写放行至高危确认链（会话放行/确认跳过/白名单/弹框）；
 *  false（默认）时越界写直接拒绝 */
public boolean writeAllowOutside() {
    return Boolean.parseBoolean(get("paths.write.allowOutside", "false"));
}
```

实时生效机制与 readAllowOutside 相同（每次使用即读 Config → 立即生效）。

### 改动 2：ConfirmGate 新增空间外写审批

[ConfirmGate.java](src/main/java/com/minion/core/tools/confirm/ConfirmGate.java) 新增方法：

```java
/** 空间外写审批（Write/Edit 越界写专用）：
 *  开关关 → 直接拒绝（不弹框，无视会话放行/确认跳过/白名单）；
 *  开关开 → 高危放行链：会话放行/确认跳过/工具白名单命中即放行，否则弹框
 *    （Y 放行本次 / N 拒绝 / W 会话放行）。
 *  与 checkWriteOutside（BrowserScreenshot 输出、受空间外读开关）语义不同，勿混用。 */
public synchronized boolean checkEscapeWrite(Tool tool, JsonObject args, String path) {
    if (!config.writeAllowOutside()) return false;
    if (sessionBypass || config.confirmSkip()) return true;
    if (isWhitelisted(tool, args)) return true;
    ConfirmUi.Decision d = ui.ask("! 越界写入 " + tool.name() + " → " + path);
    if (d == ConfirmUi.Decision.APPROVE) return true;
    if (d == ConfirmUi.Decision.REJECT) return false;
    sessionBypass = true; // APPROVE_WHITELIST / APPROVE_SESSION 均会话放行
    return true;
}
```

- 命名 `checkEscapeWrite` 与既有 `checkWriteOutside`（截图用）区分，防混淆
- 弹框文案沿用现有「越界写入」模式，与截图弹框一致
- 会话放行复用全局 `sessionBypass`：越界写与高危操作/越界读统一为全局会话放行，实现最简

### 改动 3：WriteTool / EditTool 接线

- 两工具构造各新增 `ConfirmGate` 重载（`null` 兼容旧调用/测试）；新增字段 `confirm`
- `execute` 中越界守卫命中时改为：

```java
ToolResult guard = ...; // outsideGuard / PathsGuard.errorIfOutside
if (guard != null) {
    if (confirm == null || !confirm.checkEscapeWrite(this, args, p.toString())) return guard;
}
```

- 工具不直接读 Config：开关判断收敛在 ConfirmGate 内（关 → checkEscapeWrite 返回 false → 维持原拒绝文案）

**已知权衡**：开关开且目标为工作区外**已存在**文件（覆盖）时，AgentLoop 高危确认与越界确认
两道都走，最多弹两次框（覆盖已存在文件本身即高危）；确认跳过/白名单场景无感。
合并两道确认需重构 AgentLoop 前置确认，超出本次范围，不做。

### 改动 4：设置窗基础设置页

[SettingsDialog.java](src/main/java/com/minion/gui/dialog/SettingsDialog.java)（BasicPane）：

- 新增字段 `writeOutside`：CheckBox「允许写入工作区外文件（Write/Edit）」，初值 `config.writeAllowOutside()`
- 行标签 `读逃逸:` → `空间外读:`（CheckBox 文案「允许读取工作区外文件（Read/Grep/Glob）」不变）
- 行顺序（rows 追加在空间外读行之下、确认开关行之上）：
  技能目录 / 确认白名单(工具) / 确认白名单(命令) / **空间外读** / **空间外写** / 确认开关 / 发送键
- `apply()` 增加 `config.set("paths.write.allowOutside", String.valueOf(writeOutside.isSelected()))`
  （与读开关一致：点「应用」保存，无实时 listener；确认跳过为另一独立开关）

### 改动 5：装配

[SessionManager.java](src/main/java/com/minion/gui/session/SessionManager.java) newRegistry 两行：

```java
registry.register(new WriteTool(workspace, skillsDir, tmpDir, gate));
registry.register(new EditTool(workspace, skillsDir, tmpDir, gate));
```

### 改动 6：config.properties 注释

[config.properties](src/resource/config.properties) 原注释行术语更新 + 追加空间外写键说明：

```
# 空间外读（paths.read.allowOutside）：true 时 Read/Grep/Glob 可读取工作区外文件（写入工具不受此开关影响）；false（默认）时越界读弹确认
# 空间外写（paths.write.allowOutside）：true 时 Write/Edit 越界写放行至高危确认链（确认跳过/白名单/弹框）；false（默认）时越界写直接拒绝
```

### 改动 7：测试（EditToolsTest / FileToolsTest）

按既有「读逃逸确认」测试模式（构造注入 ConfirmGate + FakeConfirmUi + 写配置键的 helper）新增：

| 用例 | 前置 | 预期 |
|---|---|---|
| write 开关关 + confirmSkip 开 | 空间外写=false、跳过=true | 仍拒绝（开关关优先，不 consult 跳过） |
| write 开关开 + confirmSkip | 空间外写=true、跳过=true | 放行，文件已写入 |
| write 开关开 + 工具白名单 | whitelist.tools 含 Write | 放行 |
| write 开关开 + 弹框 APPROVE | FakeConfirmUi(APPROVE) | 放行 |
| write 开关开 + 弹框 REJECT | FakeConfirmUi(REJECT) | 拒绝、未写入 |
| edit 开关开 + 弹框 APPROVE | 外部已存在文件 | 替换成功 |

辅助：现有 `readConfig(boolean)` 扩展为可写多个键的 helper（或新增 writeConfig 变体）。

## 明确不做

- BrowserScreenshot 越界保存不接入新开关（保持现状）
- 代码方法名 / 配置键不更名（存量兼容）
- 合并高危与越界两道确认
- Bash 重定向等非路径守卫场景（无守卫机制，不在路径守卫语义内）
- 历史设计/计划文档不回溯改名

## 验证

- `mvn compile` + `mvn test`（EditToolsTest/FileToolsTest/ConfirmGateTest 全绿）
- 手工：设置窗基础页可见「空间外读/空间外写」两行、勾选空间外写点应用后
  config.properties 落盘新键；GUI 会话内让 Write 写工作区外路径验证关=拒绝文案不变、
  开+跳过=放行、开+无跳过=弹框
- 同步检查 README 与相关文档中配置说明是否需要术语同步（用户可见处）
