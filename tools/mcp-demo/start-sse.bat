@echo off
chcp 65001 >nul
rem 启动 minion MCP 演示「远程」SSE 服务器（端口 8090；关闭本窗口即停止）
"D:\javame\jdk1.8\bin\java.exe" -jar "%~dp0target\mcp-demo.jar" sse 8090
pause
