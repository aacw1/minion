# minion

win7&jdk8代码开发工具，使用 java8开发，含有子 agent、工具（包含网页抓取）、MCP 工具扩展（stdio/SSE）、上下文压缩、风险操作确认、使用技能等功能。

## 启动（GUI）

    java -jar minion-0.1.0.jar   # 双击 jar 或命令行运行；jar 自举，无需 bat

图形界面为唯一界面（CLI 已移除）。需要 JDK 8 且自带 JavaFX：Oracle JDK 8 或 Zulu/AdoptOpenJDK 8 含 OpenJFX 的发行版；Win7 用户注意 Win7 只支持到 8u251 之前的 Oracle 版本。

jar 自举行为（启动器内置，双击 / 命令行同样生效）：

- 双击 jar（javaw、无控制台）默认隐藏控制台：javaw 直启无多余窗口；排障需看日志时，在 jar 同目录 `config.properties` 设 `boot.console=true` 恢复开窗
- 终端 `java -jar` 启动：命令行窗口不隐藏（启动器只在无控制台场景才改用 javaw；mintty/Git Bash 因 `System.console()==null` 误判为无控制台，`boot.console=true` 时仍会额外开窗——真实 Windows 控制台 cmd/PowerShell 无此问题）
- 当前 JVM 非 JDK 8 时自动切换：按 `MINION_JAVA` → `JAVA_HOME` → 常见 JDK 8 安装位置的顺序探测含 JavaFX 的 JDK 8 并重启，全程无感
- 找不到 JDK 8 但当前 JVM 能运行 → 照常启动并弹窗提示建议安装 JDK 8（可关闭，不影响使用）；当前 JVM 连 JavaFX 都没有 → 错误弹窗并退出
- 渲染管线默认 `es2,sw`（OpenGL 硬件加速优先，失败自动回退软件渲染）：JDK 8 的 D3D 管线在 VM/低端显卡上，消息区切页签等节点突发后设备状态损坏，渲染线程每帧抛 `D3DTexture.getContext` NPE（刷屏+界面卡死，2026-08-16 实证），故默认候选**不含 d3d**；旧默认纯软件渲染（`prism.order=sw`）在 4K 屏上全局卡顿（悬停/打字慢 1 秒，2026-08-17 实测），es2 在集成显卡上不可用时自动回退 sw 与旧行为一致（候选列表须逗号分隔，空格会被当作单一管线名导致启动崩溃）。手动覆盖：`set MINION_PRISM=d3d|es2|sw` 再启动
- 环境变量 `MINION_JAVA`（java.exe 全路径）优先于一切探测，显式指定即信任

首次运行在 jar 同目录自动生成 `config.properties`、`workspace.json`、`model.json`（MCP 服务器配置 `mcp.json` 在设置窗首次保存时生成）。

## 配置三件套（jar 同目录）

| 文件 | 内容 |
|---|---|
| `workspace.json` | 工作空间（名称、项目路径 work.dir、项目主说明文件 project.md、项目级技能路径 projectSkillsDir）；界面「＋ 新建工作空间」创建（名称与项目路径必填，另两项可选；均可浏览选取） |
| `model.json` | 模型配置（多模型：url/apiKey/modelName/provider/thinking/maxContextTokens 等）；设置窗「模型」页管理 |
| `config.properties` | browser（CDP 浏览器）、confirm（高危确认开关/白名单）、paths（读逃逸）、agent（工具空输出占位）、skills.dir（技能目录）、boot.console（自举控制台窗口开关，重启生效）；设置窗「基础设置」页可改（浏览器项重启生效），skills.dir 可用目录选择器浏览选取；browser.path 可用文件选择器浏览选取 |
| `mcp.json` | MCP 服务器列表（名称/传输/命令/参数/环境变量/URL/请求头/启用开关）；设置窗「MCP」页管理（列表+状态点+启用开关+新建/编辑/删除/重连） |

工作空间弹窗（新建/修改）各字段的填写要求与含义：

- **名称**：必填，不能与已有空间重名（`WorkspaceManager.isValidName`）。
- **项目路径**：必填——**界面（OK 禁用 + 行内红字）与 core 双重校验**，
  `WorkspaceManager.add` / `update` 拒绝空白 workDir（返回 false、不落盘、原配置不变）。
  它是会话的工作目录，也是文件工具与 Bash 的守卫边界；
  已落盘配置若被手改成空，读取时按软件所在目录兜底（`WorkspacePaths.workDirAbs`）。
- **项目主说明文件**：可选。内容作为「项目介绍」注入系统提示词（留空 = `<项目路径>/project.md`）。
- **项目级技能路径**：可选。递归扫描该目录下所有 `SKILL.md` / `*.skill.md`，以 `[项目]` 标注并入
  系统提示词的可用技能清单，可用 `/skill <名>` 渐进式加载正文；同名时**项目级覆盖内置**。
  留空即只有内置技能（`skills.dir`）。三项路径修改都**只对新建会话生效**。

## 快捷操作

- 发送键两种模式（基础设置「发送键」勾选即生效，默认开启）：默认 Enter 发送、Ctrl+Enter 换行；取消勾选后 Ctrl+Enter 发送、Enter 换行；Esc 关闭补全弹层 / 终止当前运行
- `@` 引用工作空间文件：按文件名反显（↑↓/鼠标选择、滚轮滚动），Enter/Tab 插入工作区根相对路径
- 补全确认反显为输入块：弹层选中的 /命令、@文件 与粘贴的大于 1000 字符长文本变为输入框上方不可编辑块，块右上角关闭按钮或空输入时 Backspace 删除；长文本粘贴在光标处插入「[粘贴块N]」占位符，发送时占位符原位展开为全文（落位 = 光标位置）；其余块与文本按顺序组合（/命令仍须在消息开头）
- `/` 斜杠命令与技能补全：/help /skills /skill <名> [参数] /compact /tokens；`/skill ` 后按技能名过滤，命令后尾随文字作为技能参数（以「用户参数: 」紧跟 `<skill>` 技能块之后注入）；命令由客户端本地执行，结果以系统行显示在聊天区，不发给模型
- 设置（右上角齿轮图标）：左列导航（基础设置 / 模型 / MCP / 关于）；模型页单击仅选中模型（查看配置用「修改」），选中后点「激活」按钮切换，选中已激活模型时按钮置灰；切换/修改参数即时生效（运行中会话下一轮生效）；基础设置页底部按钮栏「应用」（保存不关窗）与「关闭」
- 无会话时直接发送自动新建会话；发送后输入框自动清空
- 消息区发送消息强制置底；新内容增长时贴底自动跟随，向上翻过半屏暂停、翻回底半屏恢复
- 每轮回复结束显示 token 统计行（计时器图标 · 耗时 · in/out/thinking 会话累计 · ctx 上下文占比）
- 切换消息页签不重建消息区：会话视图缓存 + 增量重放（切回秒开、滚动位置保留）；长会话显示层截断保活 200 段（滚动不卡，历史头部自动收起）
- 侧栏悬停会话项显示操作按钮（重命名 / 删除）、工作空间项（修改 / 删除，重命名并入修改弹窗），移开隐藏；会话项非悬停显示最近消息时间（如 1m/5m/3h/2d，60 秒周期刷新）
- 工作空间可拖拽排序（顺序持久化，重启保持）
- 关闭会话页签 = 仅关闭页签不删除会话（运行中弹确认、确认后中断运行）；关闭后再从左侧点击会话会重新加载；删除会话/切换工作空间后右侧自动清空
- 高危操作确认卡片：右侧底部两行紧凑小卡弹出（距底 1 行），Enter 同意 / Esc 拒绝，点遮罩或侧栏不关闭；点击结果即决策，无超时判拒
- 启动懒加载：不自动打开任何会话，右侧空白占位；点击左侧会话（或新建/发送）才加载并出现页签；页签与工作空间无关，切换工作空间页签保持不变，点击旧空间页签自动切回该空间
- 关闭窗口时若有会话仍在运行会弹确认
- 运行中补充：模型运行时输入框有内容 → 发送按钮变为补充箭头，点击即把内容注入正在进行的对话（不中断流程），消息带「⤒ 运行中补充」标识
- AskUserQuestion 提问：模型需要用户信息时会调用 AskUserQuestion 工具（与 Claude Code 同名）提问，消息区显示问题与选项列表（若有），输入框进入回答模式（占位提示显示问题），输入答案发送即回传，对话继续
- 图片上传：输入框底部操作行左侧回形针按钮选图（最多 3 张、每张 ≤5MB），图片以图片块展示并随消息按 OpenAI 兼容视觉格式（content 数组）发送——需视觉模型（如 qwen-vl）；DeepSeek 不支持视觉将报错。聊天区仅显示「图片：<文件名>」占位，不渲染图片本体

## 运行状态指示器

- 状态点：会话页签与侧栏会话列表每行左侧的绿色圆点，该会话运行时呈呼吸动画（透明度 0.35↔1.0 往复，约 1.2s 周期），空闲时静止
- 正文区左下角悬浮指示器（仅当前激活会话反映）：运行中显示旋转齿轮 + 文案，齿轮约 2s/圈旋转，文案每 10s 随机轮换「正在加载中...」/「可随时补充信息...」；上下文压缩中固定显示「上下文压缩中...」（不参与轮换，压缩结束恢复）；运行结束或切换会话时隐藏；工具提问弹窗显示期间整体隐藏（防"等待用户操作"误判卡死），关闭后恢复
- 正文底部预留指示器高度留白，窗口化（非全屏）滚动到底也不会遮挡最后一行消息

## 界面图标

全部界面图标为 SVG 矢量图形（Material Symbols Outlined 线性风格，24×24 坐标系），由 `IconFactory`（gui/icon 包）集中提供，CSS（theme.css `.icon-*` 类）控制颜色与 hover 变色——不依赖系统字体，Win7 等缺字环境不再显示方块。涵盖：标题栏窗口按钮（最小化/最大化/还原/关闭）、侧栏操作按钮（修改/删除）与当前工作空间标记点、设置窗模型激活标记与 MCP 状态点、正文工具区状态图标（提问/成功/失败/子任务/工具调用/统计）、折叠段展开箭头、输入框发送/终止/上传按钮与块删除按钮。

## 会话存储

jar 同目录 `session/<workSpaceName>/`，每会话一个 JSON 文件（每轮请求完成后落盘，可安全恢复）。

## 内置工具输出上限与落盘

- 命令/搜索输出超限（Bash 30k 字符、Grep 250 条或 30k 字符）时：返回保留头部+尾部（Grep 保留前 250 条），完整结果落盘到 jar 运行目录 `<jarDir>/.session/tmp/<会话id>/`，返回中附绝对路径，可用 Read 查看；启动时自动清理 3 天前的落盘文件。

## 浏览器工具(登录、点击、查询、调试网页)

对接本机 Chrome(CDP 协议,零额外依赖)。首次使用自动启动 Chrome(默认有头窗口,便于观察调试;
自动化场景可配置 `browser.headless=true`)。配置项:

    browser.path=          # Chrome 可执行文件路径,留空自动探测常见安装位置
    browser.port=9222      # 调试端口(Chrome 默认只绑定本机,不暴露局域网)
    browser.userDataDir=./.minion/browser-profile   # 登录状态持久化目录(清空即重置)
    browser.headless=false
    browser.timeoutMs=30000

用法(模型自动调用,也可在对话里描述操作):

- `Browser`  open/back/refresh/status —— 打开页面与导航
- `BrowserEval`  执行 JS:输入、点击、提取表格数据(SPA 受控组件用 __minion_set_value 辅助)
- `BrowserScreenshot`  截图存工作区
- `BrowserDebug`  network/console/page —— 网络请求、控制台日志、页面状态

登录示例:对话里告知账号密码 → 模型用 BrowserEval 填表提交 → 登录态保存在 userDataDir,下次会话保留。

## MCP 工具扩展（stdio / SSE）

对接 MCP（Model Context Protocol）服务器，把服务器上的工具暴露给模型调用。标准 JSON-RPC 2.0 协议，兼容 Claude Code / 千问等生态的 MCP 服务器。配置在设置窗「MCP」页管理（服务器列表 + 状态点 + 启用开关 + 新建/编辑/删除/重连），落盘 `mcp.json`。

字段：

    name=playwright            # 服务器名（工具名前缀区分来源）
    transport=stdio            # stdio 或 sse
    command=npx                # stdio：可执行命令（Windows 下 .cmd/.bat 自动以 cmd /c 包装）
    args=@playwright/mcp       # 参数，每行一个
    env=KEY=VALUE              # 环境变量，每行一个
    url=                       # sse：服务端点（此时命令/参数区禁用）
    headers=K:V                # sse：请求头，每行一个

连接时机：启用服务器后首次新建/恢复会话时后台预连接（不阻塞界面），连接完成后该服务器的工具自动补充注册进所有会话（下一轮请求即可被模型调用）；与内置工具重名的自动跳过并在列表标注。

Playwright 示例（需要 Node.js 18+，可在 [nodejs.org](https://nodejs.org) 安装 LTS）：

1. 设置 → MCP → 新建：名称 `playwright`、传输 `stdio`、命令 `npx`、参数 `@playwright/mcp`，保存后勾选「启用」
2. 新建会话，对话里让模型「打开 https://www.baidu.com 并返回标题」→ 模型会调用 playwright 的浏览器工具完成操作

与浏览器（CDP）工具的关系：MCP 是独立通道，二者可共存。`config.properties` 未配置 `browser.path` 时不加载 CDP 工具（避免未装 Chrome 环境报错），MCP 不受影响。

## 模型供应商配置（deepseek / qwen）

默认对接 deepseek（thinking max）。切千问（阿里百炼 DashScope OpenAI 兼容模式）在设置窗「模型」页或 model.json 里改：

    provider=qwen
    url=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
    key=sk-你的百炼APIKey
    # 选混合模型（qwen3 系列/qwen-plus）；纯思考模型（qwq/-thinking 变体）思考不可关闭
    name=qwen3-max
    # 千问窗口通常 128k~256k；默认 900000 会超窗报 400
    maxContextTokens=131072

说明：

- `thinking=true` 时按供应商翻译思考参数：deepseek → `thinking`/`reasoning_effort`（档位至 max）；qwen → `enable_thinking` + `reasoning_effort`（档位至 xhigh，qwen3 混合模型默认开思考，关闭时显式传 `enable_thinking:false` 且不带 effort）
- qwen 下请求自动带 `stream_options: {include_usage: true}`（token 统计准确）
- `provider` 为未知值时回退 deepseek 行为
- `model.json` 缺失/为空/损坏时自动生成 deepseek + 千问两套配置（除 key 外按各自调用参数预填，key 留空待填）
- 模板参考：源码目录 `src/resource/config_deepseek.properties` / `config_qwen.properties`（仅记录，实际生效仍为 jar 同目录的配置）
