# ssh 动态工具（远程命令 + SFTP 文件操作）设计

日期：2026-09-06 · 状态：待用户审查

## 1. 背景与目标

在可插拔工具体系（browser + mysql/postgresql/oracle）中新增第 5 个插件 **ssh**：
远程执行命令 + SFTP 文件操作，供模型运维 Linux 服务器（看日志、查状态、传配置文件等）。

配置形态完全仿照数据库插件：`tools.json` 一段、设置窗「工具」页一行、连接列表 + 当前选中
（下拉框切换）、连接管理弹窗、测试连接按钮、启用开关即描述注入开关（不启用绝不注入）。

**目标**：一个插件（行）搞定 ssh 全部能力，复用既有拉模式 gate / 落盘 / GUI 刷新链路，
不引入连接池，不动 db 与 browser 既有行为。

## 2. 需求决策记录（用户已逐项确认）

1. **只做 ssh 一个动态工具**（远程执行命令 + SFTP 文件操作），**ftp 不做**。
   （用户曾考虑 ssh 与 ftp 拆分，后确认 ssh 内 SFTP 即覆盖全部需求，ftp 场景不需要。）
2. 连接选择方式与 db 相同：配置允许多个连接，**只操作「当前选中」连接**，
   工具不暴露连接参数；description 动态拼接当前连接（user@host）。
3. exec 高危策略：**危险命令才弹确认窗**（按首 token 名单判定），只读/日常命令不打断心流。
4. SFTP 动作全要：列目录 / 下载到本地 / 上传到远端 / 远端删除·改名·建目录。
   SFTP 的列目录与 exec `ls` 不重复：协议层结构化输出（大小/权限/时间），
   不依赖远端 shell，服务于传输路径发现。
5. 认证：**密码 + 私钥（可选 passphrase）都支持**。
6. GUI 表单：先单选「密码 / 私钥」再显示对应输入区（用户提出）；保存时两套字段互斥
   （切换认证方式即清空另一组，避免测试连接时认证优先级二义）。
7. SSH 库：**com.github.mwiede:jsch:2.28.7**（已核实：class 版本 52 = JDK8 ✅、
   零传递依赖、jar ~700KB、2026-08 仍活跃维护；纯 Java SSH2/SFTP）。
   对比过 Apache MINA SSHD（~3MB+ 带依赖，对本场景过重）与原版 jcraft JSch
   （2018 停更，有未修安全公告）后选定。
8. 工具形态：仿 BrowserPlugin「一个插件多个工具」风格，暴露 7 个工具
   （schema 极简、required 明确，防模型漏参）——见 §4.10。
9. put（可能覆盖远端已有文件）与 rm 一律弹确认窗；rm 只删文件/空目录，**不递归**；
   递归删除交由 exec `rm -rf`（被高危名单拦截后确认）。本地路径一律过
   `PathsGuard.errorIfOutside` 守卫（工作区/额外放行/技能/tmp 内才可读写）。
10. exec 输出口径与 BashTool 完全一致：stdout+stderr 合并、头 18k+尾 12k、超限落盘会话 tmp、
    默认超时 120s；通过**抽取公共截断助手**实现（BashTool 一并迁移 + 回归测试）。
11. 每次调用新建 SSH 连接、用完即关，不落连接池（同 DbExecutor 口径）；
    连接/认证超时上限 10s（测试连接与工具调用一致）。
12. 密码明文存 tools.json（与 db 段同一既有口径，README 已声明与 model.json apiKey 同等级）。

## 3. 架构总览

### 3.1 新增/改动一览

```
src/main/java/com/minion/
├── core/tools/
│   ├── DangerousCommands.java      [改] 增加远端扩展集判定（不动本地集合行为）
│   ├── TruncatedOutput.java        [新] 公共截断助手（自 BashTool 迁移，行为等价，OutputDump 未动）
│   ├── BashTool.java               [改] 截断逻辑改用公共助手（纯迁移，口径不变）
│   └── ssh/                        [新] 连接配置/执行器/插件/工具
│       ├── SshConnection.java         配置项（gson 直映射，同 DataSourceConfig 风格）
│       ├── SshConfig.java             仿 DbConfig：list + current + 增删改/回退规则
│       ├── SshValidator.java          纯函数表单校验（同 DataSourceValidator 风格）
│       ├── SshAuth.java               认证方式推导（password / key）
│       ├── SshExecutor.java           JSch 封装：建连/认证/exec/SFTP/超时/释放
│       ├── SshPlugin.java             implements ToolPlugin（id = "ssh"）
│       ├── SshTool.java               7 个工具中 exec 与 sftp 的公共逻辑与工厂
│       ├── SshExecTool.java           远程执行命令
│       ├── SftpLsTool.java            列目录 → Markdown 表
│       ├── SftpGetTool.java           远端 → 本地（守卫）
│       ├── SftpPutTool.java           本地 → 远端（守卫 + 确认）
│       ├── SftpRmTool.java            删除文件/空目录（确认，不递归）
│       ├── SftpMkdirTool.java         递归建目录
│       └── SftpRenameTool.java        改名/移动（同服务器内）
│   └── DangerousCommands.java       [改] 远端高危集合并入本类（§4.7，不动本地集合）
├── core/tools/plugin/
│   ├── ToolStore.java               [改] Root 加 ssh 段 + normalize
│   └── ToolPluginManager.java       [改] 装配第 5 个插件 + sshPlugin() getter
└── gui/plugin/
    ├── ToolsPane.java               [改] 行类型三分：db / ssh / browser（§4.9）
    └── SshConnectionsDialog.java    [新] 连接管理弹窗（仿 DataSourceDialog + 认证方式切换表单）

pom.xml                               [改] + com.github.mwiede:jsch:2.28.7
README.md                             [改] 可插拔工具表 + ssh 使用说明
```

### 3.2 与既有链路的关系（零侵入点）

- 工具生效仍走拉模式：`registry.register("ssh", tool)` + `PluginGate.enabled("ssh")`，
  AgentLoop 下一轮 `schemas()` 自动带上/摘掉，**无需改会话装配主干**。
- 落盘与 GUI 刷新仍走 `ToolPluginManager.saver`（写 tools.json → notifyListeners）。
- 新包 `core/tools/ssh/` 只依赖既有公共件（Tool/ToolResult/ToolContext/ConfirmGate/
  PathsGuard/OutputDump/MarkdownTable/SchemaGenerator/DangerousCommands），
  不新增对 gui / session / plugin 的依赖方向。

## 4. 详细设计

### 4.1 pom 依赖

```xml
<dependency>
  <groupId>com.github.mwiede</groupId>
  <artifactId>jsch</artifactId>
  <version>2.28.7</version>
</dependency>
```

理由（规约第 1 条要求写明）：SSH/SFTP 无 JDK 内置实现；该库 class 版本 52（JDK8）、
零传递依赖（已核实 POM）、~700KB，无 shade 冲突风险；maintainer 持续跟进安全与算法
（2.28.x 2026-08 仍在发版）。jar 体积约 +0.7MB。

### 4.2 `SshConnection`（配置项，tools.json `connections[]` 元素）

```java
public class SshConnection {
    public String name = "";          // 标识名：同一插件内唯一，可修改（同数据源）
    public String host = "";          // 主机（域名/IP；IPv6 允许）
    public int port = 22;
    public String user = "";
    public String password = "";      // 认证方式=密码时使用
    public String privateKeyPath = ""; // 认证方式=私钥时使用（绝对路径或相对工作区）
    public String passphrase = "";    // 私钥口令，可空
}
```

互斥规则集中在 `SshAuth`：`privateKeyPath` trim 非空 → key 认证；否则 → password 认证。
GUI 保存时按所选方式清空另一组（见 §4.9），工具/测试连接一律按 `SshAuth` 推导，
不存在「两套都填」的持久状态（手改文件双填时 key 优先，文档注明）。

### 4.3 `SshConfig`（仿 DbConfig 自包含实现）

字段与 db 同构：`enabled` / `current` / `connections`（CopyOnWriteArrayList）。
方法：`currentConnection()` / `find(name)`（trim+忽略大小写）/ `names()` /
`add`（首个自动为 current）/ `replace`（改名同步 current）/ `remove`（回退第一个或清空）/
`setCurrent`（不存在则忽略）。

**不把 DbConfig 泛型化的理由**：DbConfig 方法均强依赖 `DataSourceConfig.name`，
泛型化要动已稳定的 db 链路与既有单测；ssh 侧复制 ~80 行模式代码换来零回归风险，
符合 YAGNI 与「现有代码若无碍当前目标则不动」原则。

### 4.4 `SshValidator`（纯函数，仿 DataSourceValidator）

`validate(name, host, port, user, authKind, password, privateKeyPath, all, originalName)`：

- 标识名非空 / ≤40 / 同插件内唯一（改名自身不算重复）——与 db 同规则；
- host 非空（不做 IP 格式强校验，兼容内网别名）；
- port 1~65535；
- user 非空；
- authKind=password → 密码非空（trim 判空，保存保留原样——同 DataSourceValidator 注释口径）；
- authKind=key → 私钥路径非空；passphrase 任意。

**不做 IO 校验**（私钥文件存在性等留给测试连接/工具调用时报错，与 db 不校验 URL 可达性同思路）。

### 4.5 `SshExecutor`（JSch 封装，每次新建即关）

- `connect(cfg)`：`JSch` 实例 → 按 `SshAuth` 加 `UserInfo`/`Identity` →
  `session.setTimeout(10000)`（连接与 socket 读超时上限）→ `connect(10000)`。
  未知主机指纹：默认 `StrictHostKeyChecking=no`（与多数轻量客户端一致；known_hosts
  维护超出本工具范围，在文档「风险」注明）。
- `exec(cfg, command, timeoutSeconds, sink)`：开 `exec` channel，命令原样发送
  （服务器按默认 shell 处理；描述中提示需要登录 shell 环境时用 `bash -lc '...'` 包裹）；
  stdout/stderr 分别读、合并到同一字符流（stderr 行标注 `[stderr]` 前缀，与 exit code 一起返回）。
  读取按块流式解码（跨块 UTF-8 续读 + 前缀只加在真正行首，块边界无伪影）。
- 超时：主线程等 `exitStatus` 至多 timeoutSeconds（工具层默认 120s），超时先
  `channel.disconnect()` 收割已产出的部分输出（随超时错误文案一并返回，与 BashTool
  超时输出口径一致），随后断开会话；**注明远端可能残留孤儿进程**（与 DbExecutor
  300s「超时不一定能打断」同口径提示）。
- SFTP：`openChannel("sftp")` → `ChannelSftp`；每次操作（ls/get/put/rm/mkdir/rename）
  独立建连执行后关闭。
- 一切受检异常转错误文案：取异常首行（`message` 换行截断），与 `DbExecutor.firstLine` 同风格；
  密码/私钥错误时 jsch 抛认证异常，文案带「认证失败（密码或私钥不正确）」。
- 连接失败/超时/中断等错误返回 `ToolResult.error` 交给模型自调（工具错误契约），不抛 LlmException。

### 4.6 截断助手抽取（`OutputDump` 增静态方法）

把 BashTool 的「累积写入 + 头尾截断组装」（`HEAD_MAX=18000` / `TAIL_MAX=12000` /
`TOTAL_MAX=30000`、代理对边界、超限落盘、未超限删盘零痕迹、落盘失败降级文案）抽为
`OutputDump` 的公共静态助手（如 `TruncatedOutput` 小类：`append(line)` / `finish()`），
BashTool 改为调用它。**行为逐字节等价**——迁移后跑既有 BashToolTest 与新增单测验证
（P0 回归点：内存保留 TOTAL_MAX 而不仅是 HEAD_MAX 的尾部可补逻辑、emoji 高代理边界、
零痕迹删盘）。

### 4.7 高危名单（远端扩展，不动本地判定）

`DangerousCommands` 增加远端集合与判定入口（如 `isDangerousRemote`），内容 =
本地现有集合 ∪ { `systemctl`, `service`, `halt`, `poweroff`, `userdel`, `passwd`,
`groupdel`, `apt`, `yum`, `dnf`, `make install`, `pip install`… 安装类首 token }。
本地 BashTool 继续用原集合，行为不变。首 token 解析复用 `DangerousCommands.firstToken`
（引号剥离 / basename / .exe 已覆盖；远端 Linux 为主，.exe 判定无害）。

判定结果只用于 `SshExecTool.isHighRisk`（命中 → AgentLoop 弹确认窗），非硬拦截。

### 4.8 `SshPlugin implements ToolPlugin`

- `id()` = `"ssh"`；`displayName()` = `"ssh"`；`statusText()` 恒空——与 db 行同口径：
  当前连接/（无连接）均由行内下拉框表达，状态列不重复占位（行布局见 4.9）；
- `canEnable()` = `!config.names().isEmpty()`（无连接时勾选框置灰，同 db）；
- `createTools(ctx)` 产 7 个工具（工厂见 4.10），共享 config、workspace/skillsDir/tmpDir/
  confirmGate（来自 ToolContext）；
- `testConnection(name)`：连接+认证即断，同步阻塞至多 10s，返回
  `TestResult{ok,message,elapsedMs}`（复用 DbPlugin.TestResult 形态，独立实现）；
- 连接 CRUD（add/update/remove/setCurrent）同 DbPlugin，改动即 `save()` 落盘。

### 4.9 GUI

**ToolsPane 行类型改造**：现在 `if (db != null) … else 浏览器` 两分。改为三分：
`dbPlugin(id)` 命中 → 数据源下拉 + 「数据源管理」；`"ssh".equals(id)` →
当前连接下拉 + 「连接管理」（按钮文字带连接数）；否则浏览器 → 「配置」。
行数固定 4 → 5。canEnable 置灰提示按行类型给文案（ssh：需先建连接）。

**SshConnectionsDialog（仿 DataSourceDialog + 认证方式切换）**：
列表 + 新建/修改/删除 + 测试连接 + 选中项详情。表单字段：
标识名 / 主机 / 端口 / 用户名 / **认证方式单选（密码 | 私钥）** → 动态显示对应区
（密码框；或 私钥路径 + 浏览文件按钮 + passphrase 框）。保存时按 `SshAuth` 规则互斥落库。
测试连接在后台线程跑（同 DataSourceDialog 的 handler 模式），按钮防重复点击，
结果用 PluginUi 提示。删除当前连接时 SshConfig 自动回退（同 DbConfig），
外层下拉由 ToolPluginManager 监听器刷新。

### 4.10 工具集（7 个，插件标签均为 "ssh"）

工具名采用首字母大写驼峰（与 DbMysql/BrowserEval 一致）：

| 工具 | 参数（required） | 返回 | isHighRisk |
|---|---|---|---|
| `SshExec` | command(+), timeoutSeconds? | stdout+stderr+exit code；超限截断落盘 | 远端名单命中 |
| `SftpLs` | path(+) | Markdown 表：名称/类型/权限/大小/修改时间（行数多则截断提示用 exec ls 细化） | false |
| `SftpGet` | remotePath(+), localPath(+) | 成功文案 + 提示「用 Read 查看」 | false |
| `SftpPut` | localPath(+), remotePath(+) | 成功文案（字节数/耗时） | true（一律） |
| `SftpRm` | path(+) | 成功/不存在文案 | true（一律） |
| `SftpMkdir` | path(+) | 成功文案（含已存在） | false |
| `SftpRename` | srcPath(+), dstPath(+) | 成功文案 | false |

- description 动态拼接：当前连接与认证信息 + 动作用法示例；
  无当前连接 → 「未选择连接，调用返回提示，请在 设置 → 工具 → ssh 选择」（同 db 口径）。
- schema 用 `SchemaGenerator.objectSchema`；required 精简为动作必填参数。
- 本地路径守卫：SftpGet/Put 先 `PathsGuard.errorIfOutside(workspace, skillsDir, tmpDir, p)`，
  越界返回与 Read/Write 同款错误文案；本地路径相对工作区解析。
- 确认文案走既有 ConfirmGate（GuiConfirmUi → ConfirmSheet），工具不感知 UI。

### 4.11 错误处理与提示文案汇总（对齐既有风格）

- 未配置/未选连接：「ssh 插件未选择连接，请在 设置 → 工具 → ssh 中选择」
- 认证失败：「ssh 认证失败（10.0.0.5:22 / root）：密码或私钥不正确」
- 连接超时（10s）：「连接超时（10s）：host:port …」
- exec 超时：「命令超时（120s），已断开连接，远端可能残留进程: command…」
- 远端不存在/无权限：透传 jsch 消息首行
- put 前确认文案说明会覆盖同名远端文件；rm 文案说明删除不可恢复

## 5. 测试计划

### 5.1 单元测试（junit4，不新增测试依赖、不连真实服务器）

- `SshValidatorTest`：标识名规则 / host 空 / port 越界 / user 空 / 两种认证的必填分支；
- `SshConfigTest`：add/replace/remove/current 回退（首添自动选中、删当前回退第一个、
  current 失效置空）——镜像 DbConfigTest 覆盖点；
- `SshAuthTest`：key 优先 / 全空 / trim 边界；
- `DangerousCommands` 远端判定新增测试：远端名单命中、本地名单行为不变（回归）；
- 截断助手：迁移后 BashTool 既有测试全绿 + 新助手单测（超限落盘/未超限零痕迹/emoji 边界）；
- `SshTool` 族错误路径（不触网）：未选连接文案 / 缺参 / SftpGet-Put 本地路径越界 /
  SftpRm 不存在的远端路径错误透传（executor 注入 fake，仿 DbToolTest 用不可达地址
  验证「不会真去建连」的写法：127.0.0.1:1 连接必然失败，暴露死等回归）。

### 5.2 手工验证清单（进实施计划）

1. 设置页出现第 5 行；无连接时启用置灰；新建连接后可启用。
2. 认证方式单选切换只显示对应输入区；保存后 JSON 互斥正确。
3. 测试连接：正确密码 ok；错密码/错端口/超时给友好文案。
4. 启用在下一轮会话生效；关闭后模型看不到任何 ssh 工具。
5. exec 常用命令、长输出截断落盘（Read 能看完整文件）、exit code 非零返回失败。
6. exec `rm -rf /tmp/x`、`systemctl stop nginx` 弹确认窗；`ls`/`tail` 不弹。
7. SftpLs 表格渲染；SftpGet 落工作区后用 Read 可读；越界路径被拒。
8. SftpPut 覆盖场景弹确认；SftpRm 弹确认且不递归；SftpMkdir 递归建目录。
9. 老 sshd（仅 ssh-rsa）协商失败时的错误文案可读（风险项，非阻塞）。

## 6. 风险与缓解

- **算法协商兼容**：旧服务器默认 ssh-rsa 签名在 jsch 2.28 默认关闭；文档注明
  必要时可在 SshExecutor 加 config 开关（本期不做，遇到再补）。
- **密码明文**：既有 db 段同口径，README 已声明。
- **exec 超时残留孤儿进程**：断开连接并明示，不谎报成功（同 DbExecutor 表述口径）。
- **StrictHostKeyChecking=no**：不做指纹管理，限内网/测试服务器使用；README 注明。
- **工具数增加**：schemas 多 7 条（每条约 80~150 字），上下文预算内；后续若紧张可
  用压缩策略缓解，非本期问题。

## 7. 非目标（YAGNI）

- 不做 ftp 工具；
- 不做连接池/会话复用/跳板机（ProxyJump）/隧道/端口转发；
- 不做 known_hosts 指纹管理；不做密钥代理（agent）转发；
- 不泛型化 DbConfig；不改本地 BashTool 危险名单与截断行为；
- 不支持 SFTP 递归删除（由 exec rm -rf 覆盖，弹确认）。

## 8. 文档更新清单

- README：可插拔工具表格加 `ssh` 行；新增小节说明 ssh 段配置字段、
  认证方式互斥、7 个工具与高危确认范围、StrictHostKeyChecking 提示。
- 本文档实施期如与计划/代码有差异，在文末追加「实施期修正记录」（沿用 db 文档惯例）。

## 实施期修正记录（计划与本文档的差异，以计划/代码为准）

- 截断助手落位为独立文件 TruncatedOutput（规格初稿写 OutputDump 静态成员）；
- `SshPlugin.statusText()` 恒空（初稿为「当前连接名/无连接提示」）——与 db 行同口径：
  当前连接/（无连接）均由行内下拉框表达，状态列不重复占位（§4.8 已同步）；
- final review 修复轮（exec 输出链路，与计划 Task 3 代码快照差异、已实施，勿照抄快照）：
  - exec 输出读取改为 LineDecoder 跨块 UTF-8 流式解码（原按 8192 字节块硬解码：
    多字节字符跨块产生替换符、`[stderr]` 前缀在块边界误插）；
  - exec 成功与超时路径均**先 join reader 再 close 累积器**（原 close 先于 join，
    通道刚关闭时末块 ≤8KB 可能进不了 >30k 截断的落盘文件）；
  - exec 超时错误附已产出的部分输出（原直接抛错丢弃已捕获内容；与 BashTool 超时口径对齐）；
  - SshExecutor 类 Javadoc「exec 默认 120s」措辞修正——executor 不设默认，
    默认值在 SshExecTool 层（DEFAULT_TIMEOUT=BashTool 同值）。
