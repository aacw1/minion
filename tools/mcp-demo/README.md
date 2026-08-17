# mcp-demo — minion MCP 演示服务器（开发/测试用）

独立 Maven 模块，不进主构建。给 minion 的 MCP 功能提供联调服务器，
同 jar 双模式覆盖两种传输：

- `stdio`：本地进程（stdin/stdout 按行 JSON-RPC），工具：`time_now`、`add_numbers`
- `sse <端口>`：远程 HTTP（POST / 请求 + GET / 事件流，绑定 0.0.0.0），工具：`echo_text`、`today_date`、`random_number`

## 构建（需 JDK8）

    JAVA_HOME="D:/javame/jdk1.8" mvn -f tools/mcp-demo/pom.xml clean package
    # 产物：tools/mcp-demo/target/mcp-demo.jar

## 运行

    D:/javame/jdk1.8/bin/java -jar tools/mcp-demo/target/mcp-demo.jar stdio
    D:/javame/jdk1.8/bin/java -jar tools/mcp-demo/target/mcp-demo.jar sse 8090
    # 或双击 start-sse.bat 启动 SSE 服务器

## 在 minion 中配置（jarDir/mcp.json）

- demo-local：`command=D:/javame/jdk1.8/bin/java`，`args=[-jar, <绝对路径>/mcp-demo.jar, stdio]`，`transport=stdio`
- demo-remote：`url=http://127.0.0.1:8090/`，`transport=sse`

协议：JSON-RPC 2.0 over stdio / HTTP-SSE，初始化 protocolVersion `2024-11-05`，
实现 initialize/ping/tools/list/tools/call。
