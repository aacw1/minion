个人助手，代码开发工具，使用java8开发，只有基础依赖，打完包2M，对接多供应商大模型（deepseek/qwen，OpenAI 兼容协议），含有子agent、工具（包含网页抓取）、上下文压缩、风险操作确认、使用技能等功能。个人学习项目。解决公司内网win7不能使用编程助手的问题。结合superpowers使用效率大增。

使用示例，文字根据不同情况是有不同颜色的,粘贴进来的只有文本：

PS E:\javame\code\code2\minion\target> java -jar .\minion-0.1.0.jar
[minion] 配置文件: E:\javame\code\code2\minion\target\config.properties
模型: deepseek-v4-flash
上下文上限: 900000 tokens
工作空间: E:\javame\code\code2\minion\target\.
项目说明: E:\javame\code\code2\minion\target\.\project.md (未创建)
技能目录: E:\javame\code\code2\minion\target\.\skills
会话存储: E:\javame\code\code2\minion\target\.\.minion\sessions
minion — 代码开发助手  (输入 /help 查看命令)
输入 /skills 查看所有技能，/skill <技能名> 加载技能
(检测到上次会话，输入 /resume 恢复)
> /skill using-superpowers
! 已加载技能: using-superpowers
已加载技能: using-superpowers
> 开发一个网页小游戏
【思考】用户说"开发一个网页小游戏"，这是一个非常模糊的需求。根据规则，用户指令不明确、信息不足时，先列出需要补充的问题，等待用户回答后再行动。

同时，根据 using-superpowers 技能的指示，在创建任何创意工作（创建功能、构建组件、添加功能或修改行为）之前，我必须使用 brainstorming 技能。让我先读取 brainstorming 技能文件。

技能目录可能在工作路径之外，所以直接用 Read 工具读取绝对路径：E:\javame\code\code2\minion\target\skills\brainstorming\SKILL.md
──────────────────────────────
【回复】
[工具] Read → {"path":"E:\\javame\\code\\code2\\minion\\target\\skills\\brainstorming\\SKILL.m...
· Read: ---
(151 lines)
【思考】根据 brainstorming 技能，我需要：
1. 先探索项目上下文
2. 逐个提出澄清问题
3. 提出 2-3 种方案
4. 呈现设计
5. 写设计文档
6. 等等

用户的需求是"开发一个网页小游戏"，非常模糊。我应该先检查工作目录的上下文，然后问澄清问题（一次一个）。

先看看工作目录里有什么。
──────────────────────────────
【回复】我先了解一下当前项目环境，然后再向你提问。
[工具] Bash → {"command":"pwd && ls -la"}
· Bash: /e/javame/code/code2/minion/target
(17 lines)
【思考】当前工作目录是 minion 的构建输出目录（target），不是项目源码目录。这里没有现成的网页游戏项目。

用户的需求"开发一个网页小游戏"非常模糊。根据 brainstorming 技能，我需要一次一个问题地询问澄清问题，理解目的、约束、成功 标准。

第一个问题应该是关于游戏类型或意图的。让我用 TodoWrite 创建 brainstorming 清单，然后开始提问。

按照技能要求，先创建任务清单。然后一次问一个问题。
──────────────────────────────
【回复】我正在使用 **brainstorming** 技能来明确你的需求（在写任何代码之前，需要先理解你想做什么）。
[工具] TodoWrite → {"action":"update","items":[{"text":"探索项目上下文","done":true},{"text":"逐个提出澄清问题（游戏类...
· TodoWrite: 任务清单:
(9 lines)
【思考】当前目录是 minion 项目的构建输出目录（target），没有现成的游戏项目。所以这是一个全新的项目。

现在开始提问，一次一个问题。第一个问题：想做什么类型的游戏？
──────────────────────────────
【回复】当前目录是构建输出目录，没有现成的游戏项目，所以我们会从零开始做一个全新的小游戏。

在动手之前，我需要先弄清几个问题（一次一个）：

**第一个问题：你想做什么类型的游戏？** 比如：

- A. 经典街机类（贪吃蛇、俄罗斯方块、2048、扫雷）
- B. 反应/操作类（接水果、弹球、点泡泡）
- C. 休闲益智类（连连看、数独、记忆翻牌）
- D. 动作冒险类（平台跳跃、射击）
- E. 其他你心里已有的想法

选一个或直接描述你的想法都可以。
* 8.6s · in 20.2k · out 835 · thinking 349 · ctx 4.7k/900k (1%)

## 模型供应商配置（deepseek / qwen）

默认对接 deepseek（thinking max）。切千问（阿里百炼 DashScope OpenAI 兼容模式）改 jar 同目录 config.properties 的 5 项：

    model.provider=qwen
    model.url=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
    model.key=sk-你的百炼APIKey
    # 选混合模型（qwen3 系列/qwen-plus）；纯思考模型（qwq/-thinking 变体）思考不可关闭
    model.name=qwen3-max
    # 千问窗口通常 128k~256k；默认 900000 会超窗报 400
    model.maxContextTokens=131072

说明：

- `model.thinking=true` 时按供应商翻译思考参数：deepseek → `thinking`/`reasoning_effort`；qwen → `enable_thinking`（qwen3 混合模型默认开思考，关闭时同样显式传 `enable_thinking:false`）
- qwen 下请求自动带 `stream_options: {include_usage: true}`（token 统计准确）
- `model.provider` 为未知值时回退 deepseek 行为
- 模板参考：源码目录 `src/resource/config_deepseek.properties` / `config_qwen.properties`（仅记录，实际生效仍为 jar 同目录的 `config.properties`）

## Win7 控制台中文乱码说明（2026-08-10 修复）

minion 在 Windows 上的控制台编码策略：Win8.1+ 自动切控制台代码页 65001 并以 UTF-8 输出；
**Win7 不切代码页，输出跟随控制台现有代码页（中文系统 936→GBK）**——因为 Win7 控制台
在 65001 下渲染中文损坏（点阵字体无 CJK 字形），而 936 下渲染正常。cmd 直接 `java -jar` 即可。

**git bash 老版本**（如 2.x 早期，mintty 按 GBK 解码 UTF-8 字节）：标题栏右键 →
Options → Text → Character set → 选 **UTF-8**；或直接升级 Git for Windows（新版默认 UTF-8）。

注意：Windows 上不要加 `-Dfile.encoding=UTF-8` 启动参数，会导致真实控制台编码判断错误、重新乱码。
