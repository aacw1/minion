# minion

个人助手，代码开发工具，使用 java8 开发，单 fat jar 分发约 2.5M（gson/okhttp/snakeyaml/flexmark 等全量依赖打进去，JavaFX 用 JDK 自带 jfxrt 不入包），对接多供应商大模型（deepseek/qwen，OpenAI 兼容协议），含有子 agent、工具（包含网页抓取）、上下文压缩、风险操作确认、使用技能等功能。解决公司内网 win7 不能使用编程助手的问题。结合 superpowers 使用效率大增。

## 启动（GUI）

    java -jar minion-0.1.0.jar   # 双击 jar 或命令行运行；jar 自举，无需 bat

图形界面为唯一界面（CLI 已移除）。需要 JDK 8 且自带 JavaFX：Oracle JDK 8 或 Zulu/AdoptOpenJDK 8 含 OpenJFX 的发行版；Win7 用户注意 Win7 只支持到 8u251 之前的 Oracle 版本。

jar 自举行为（启动器内置，双击 / 命令行同样生效）：

- 双击 jar（javaw、无控制台）自动开启控制台窗口，日志可见
- 当前 JVM 非 JDK 8 时自动切换：按 `MINION_JAVA` → `JAVA_HOME` → 常见 JDK 8 安装位置的顺序探测含 JavaFX 的 JDK 8 并重启，全程无感
- 找不到 JDK 8 但当前 JVM 能运行 → 照常启动并弹窗提示建议安装 JDK 8（可关闭，不影响使用）；当前 JVM 连 JavaFX 都没有 → 错误弹窗并退出
- 默认软件渲染（`prism.order=sw`）：JDK 8 的 D3D 管线在 VM/低端显卡上，消息区切页签等节点突发后设备状态损坏，渲染线程每帧抛 `D3DTexture.getContext` NPE（刷屏+界面卡死，2026-08-16 实证）。想用硬件渲染：`set MINION_PRISM=d3d` 再启动
- 环境变量 `MINION_JAVA`（java.exe 全路径）优先于一切探测，显式指定即信任

首次运行在 jar 同目录自动生成 `config.properties`、`workspace.json`、`model.json`。

## 配置三件套（jar 同目录）

| 文件 | 内容 |
|---|---|
| `workspace.json` | 工作空间（名称、work.dir、project.md）；界面「＋ 新建工作空间」创建（work.dir 用系统文件夹选择框选，project.md 可文件选择器选取；新建/修改弹窗同样支持） |
| `model.json` | 模型配置（多模型：url/apiKey/modelName/provider/thinking/maxContextTokens 等）；⚙ 设置窗「模型」页管理 |
| `config.properties` | browser（CDP 浏览器）、confirm（高危确认开关/白名单）、paths（读逃逸）、skills.dir（技能目录）；⚙ 设置窗「基础设置」页可改（浏览器项重启生效），skills.dir 可用目录选择器浏览选取；browser.path 可用文件选择器浏览选取 |

## 快捷操作

- 发送键两种模式（基础设置「发送键」勾选即生效，默认开启）：默认 Enter 发送、Ctrl+Enter 换行；取消勾选后 Ctrl+Enter 发送、Enter 换行；Esc 关闭补全弹层 / 终止当前运行
- `@` 引用工作空间文件：按文件名反显（↑↓/鼠标选择、滚轮滚动），Enter/Tab 插入工作区根相对路径
- 补全确认反显为输入块：弹层选中的 /命令、@文件 与粘贴的 ≥100 字符长文本变为输入框上方不可编辑块，✕ 或空输入时 Backspace 删除；发送时块与文本按顺序组合（/命令仍须在消息开头）
- `/` 斜杠命令与技能补全：/help /skills /skill <名> /compact /tokens；`/skill ` 后按技能名过滤；命令由客户端本地执行，结果以系统行显示在聊天区，不发给模型
- ⚙ 设置（右上角）：左列导航（基础设置 / 模型 / 关于）；切换模型、修改参数即时生效（运行中会话下一轮生效）；基础设置页底部按钮栏「应用」（保存不关窗）与「关闭」
- 无会话时直接发送自动新建会话；发送后输入框自动清空
- 消息区发送消息强制置底；新内容增长时贴底自动跟随，向上翻过半屏暂停、翻回底半屏恢复
- 每轮回复结束显示 token 统计行（⏱ 耗时 · in/out/thinking 会话累计 · ctx 上下文占比）
- 切换消息页签不重建消息区：会话视图缓存 + 增量重放（切回秒开、滚动位置保留）；长会话显示层截断保活 200 段（滚动不卡，历史头部自动收起）
- 侧栏悬停会话项显示操作按钮（✎ 重命名 / ✕ 删除）、工作空间项（⚙ 修改 / ✕ 删除，重命名并入修改弹窗），移开隐藏；会话项非悬停显示最近消息时间（如 1m/5m/3h/2d，60 秒周期刷新）
- 工作空间可拖拽排序（顺序持久化，重启保持）
- 关闭会话页签 = 删除会话（有确认）；删除会话/切换工作空间后右侧自动清空
- 高危操作确认卡片：右侧底部两行紧凑小卡弹出（距底 1 行），Enter 同意 / Esc 拒绝，点遮罩或侧栏不关闭；点击结果即决策，无超时判拒
- 启动后自动激活最近会话：右侧直接显示其历史消息，直接输入会继续该会话；切换工作空间后自动激活新空间首个会话（JavaFX 页签自动选中行为，已确认接受）
- 关闭窗口时若有会话仍在运行会弹确认
- 运行中补充：模型运行时输入框有内容 → 发送按钮变为补充箭头，点击即把内容注入正在进行的对话（不中断流程），消息带「⤒ 运行中补充」标识
- AskUserQuestion 提问：模型需要用户信息时会调用 AskUserQuestion 工具（与 Claude Code 同名）提问，消息区显示问题与选项列表（若有），输入框进入回答模式（占位提示显示问题），输入答案发送即回传，对话继续
- 图片上传：输入框底部操作行左侧回形针按钮选图（最多 3 张、每张 ≤5MB），图片以图片块展示并随消息按 OpenAI 兼容视觉格式（content 数组）发送——需视觉模型（如 qwen-vl）；DeepSeek 不支持视觉将报错。聊天区仅显示「图片：<文件名>」占位，不渲染图片本体

## 会话存储

jar 同目录 `session/<workSpaceName>/`，每会话一个 JSON 文件（每轮请求完成后落盘，可安全恢复）。

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

## 模型供应商配置（deepseek / qwen）

默认对接 deepseek（thinking max）。切千问（阿里百炼 DashScope OpenAI 兼容模式）在 ⚙ 设置窗「模型」页或 model.json 里改：

    provider=qwen
    url=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
    key=sk-你的百炼APIKey
    # 选混合模型（qwen3 系列/qwen-plus）；纯思考模型（qwq/-thinking 变体）思考不可关闭
    name=qwen3-max
    # 千问窗口通常 128k~256k；默认 900000 会超窗报 400
    maxContextTokens=131072

说明：

- `thinking=true` 时按供应商翻译思考参数：deepseek → `thinking`/`reasoning_effort`；qwen → `enable_thinking`（qwen3 混合模型默认开思考，关闭时同样显式传 `enable_thinking:false`）
- qwen 下请求自动带 `stream_options: {include_usage: true}`（token 统计准确）
- `provider` 为未知值时回退 deepseek 行为
- `model.json` 缺失/为空/损坏时自动生成 deepseek + 千问两套配置（除 key 外按各自调用参数预填，key 留空待填）
- 模板参考：源码目录 `src/resource/config_deepseek.properties` / `config_qwen.properties`（仅记录，实际生效仍为 jar 同目录的配置）
