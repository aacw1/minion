# minion

个人助手，代码开发工具，使用 java8 开发，单 fat jar 分发约 2.5M（gson/okhttp/snakeyaml/flexmark 等全量依赖打进去，JavaFX 用 JDK 自带 jfxrt 不入包），对接多供应商大模型（deepseek/qwen，OpenAI 兼容协议），含有子 agent、工具（包含网页抓取）、上下文压缩、风险操作确认、使用技能等功能。解决公司内网 win7 不能使用编程助手的问题。结合 superpowers 使用效率大增。

## 启动（GUI）

    minion.bat                # 推荐：自动探测含 JavaFX 的 JDK 8 并启动
    java -jar minion-0.1.0.jar   # 直接运行（须用含 JavaFX 的 JDK 8 的 java）

图形界面为唯一界面（CLI 已移除）。需要 JDK 8 且自带 JavaFX：Oracle JDK 8 或 Zulu/AdoptOpenJDK 8 含 OpenJFX 的发行版；Win7 用户注意 Win7 只支持到 8u251 之前的 Oracle 版本。若 PATH 里的 `java` 不是含 JavaFX 的 JDK 8（会报 `NoClassDefFoundError: javafx/application/Application`），请用 `minion.bat` 启动——它按 `MINION_JAVA` → `JAVA_HOME` → 常见 JDK 8 安装位置的顺序探测，全部落空时给出清晰错误提示。

首次运行在 jar 同目录自动生成 `config.properties`、`workspace.json`、`model.json`。

## 配置三件套（jar 同目录）

| 文件 | 内容 |
|---|---|
| `workspace.json` | 工作空间（名称、work.dir、project.md）；界面「＋ 新建工作空间」创建（work.dir 可用系统文件夹选择框选） |
| `model.json` | 模型配置（多模型：url/apiKey/modelName/provider/thinking/maxContextTokens 等）；⚙ 设置窗「模型」页管理 |
| `config.properties` | browser（CDP 浏览器）、confirm（高危确认开关/白名单）、paths（读逃逸）、skills.dir（技能目录）；⚙ 设置窗「基础设置」页可改（浏览器项重启生效） |

## 快捷操作

- Ctrl+Enter 发送、Enter 换行
- ⚙ 设置（右上角）：模型 / 基础设置 / 关于；切换模型、修改参数即时生效（运行中会话下一轮生效）
- 无会话时直接发送自动新建会话；发送后输入框自动清空
- 消息区滚动条贴底时随新内容自动滚动，离开底部暂停；拖回底部恢复
- 关闭会话页签 = 删除会话（有确认）；删除会话/切换工作空间后右侧自动清空
- 关闭窗口时若有会话仍在运行会弹确认

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
- 模板参考：源码目录 `src/resource/config_deepseek.properties` / `config_qwen.properties`（仅记录，实际生效仍为 jar 同目录的配置）
