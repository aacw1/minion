package com.minion.cli;

import com.google.gson.JsonObject;
import com.minion.core.agent.AgentUi;
import com.minion.core.util.Ansi;
import com.minion.core.tools.ToolResult;

/** 终端渲染：颜色开关、流式增量输出 */
public class Renderer implements AgentUi {

    private final boolean color;
    /** REPL 下 JLine 已回显用户输入，关闭二次打印避免重复 */
    private boolean echoUser = true;
    /** 思考流式分块 1-2 字一条，【思考】标签只在思考块开头打印一次 */
    private boolean thinkingOpen = false;
    /** 正文【回复】标签每块只打一次；新一轮思考/工具调用后复位 */
    private boolean contentOpen = false;
    /** 流式输出最后是否停在行首：统计行要独占一行时据此补换行 */
    private boolean streamAtLineStart = true;

    /** 思考与回复之间的分隔线 */
    private static final String SEPARATOR = "──────────────────────────────";

    /**
     * REPL 提示符：只用普通 >。❯ ⏱ 🔧 ✗ ⚠ ⌁ 等 Unicode 符号在 mintty（git bash）
     * 默认字体链（Consolas + SimSun 回退）中缺失，被渲染为 ?（见测试 SafeGlyphs）。
     * 全 CLI 格式化输出只允许 ASCII 或已实测可渲染字符（· × → ─ 全角标点、中文）。
     */
    static final String PROMPT = "> ";

    public Renderer(boolean color) { this.color = color; }

    public void setEchoUser(boolean echoUser) { this.echoUser = echoUser; }

    private String text(String s, String code) {
        return color ? Ansi.wrap(s, code) : s;
    }

    /** REPL 统计行：绿色 */
    public String green(String s) { return text(s, Ansi.GREEN); }

    /** REPL 欢迎横幅：青色加粗 */
    public String wrapBanner(String s) { return color ? Ansi.wrap(s, Ansi.CYAN + ";" + Ansi.BOLD) : s; }

    /** 统计行：若正文没有以换行收尾，先补一个换行，保证统计行永远独占一行 */
    public void printlnStats(String line) {
        if (!streamAtLineStart) System.out.println();
        System.out.println(text(line, Ansi.GREEN));
        streamAtLineStart = true;
    }

    @Override
    public void onUserMessage(String text) {
        thinkingOpen = false;
        contentOpen = false;
        if (!echoUser) return; // JLine 已回显，避免重复
        System.out.println();
        System.out.println(text((PROMPT + text), Ansi.CYAN + ";" + Ansi.BOLD));
        streamAtLineStart = true;
    }

    @Override
    public void onThinking(String delta) {
        if (!thinkingOpen) {
            thinkingOpen = true;
            contentOpen = false; // 新一轮思考后正文重新打【回复】
            System.out.print(text("【思考】", Ansi.YELLOW + ";" + Ansi.BOLD));
            streamAtLineStart = false;
        }
        System.out.print(text(delta, Ansi.GRAY));
        System.out.flush();
        streamAtLineStart = delta.endsWith("\n");
    }

    @Override
    public void onContent(String delta) {
        if (!contentOpen) {
            contentOpen = true;
            boolean afterThinking = thinkingOpen;
            thinkingOpen = false; // 思考结束，正文开始
            if (afterThinking) {
                System.out.println();
                System.out.println(text(SEPARATOR, Ansi.GRAY));
                streamAtLineStart = true;
            }
            System.out.print(text("【回复】", Ansi.CYAN + ";" + Ansi.BOLD));
            streamAtLineStart = false;
        }
        System.out.print(delta);
        System.out.flush();
        streamAtLineStart = delta.endsWith("\n");
    }

    @Override
    public void onToolCall(String name, JsonObject args) {
        thinkingOpen = false; // 思考结束，开始调工具
        contentOpen = false;
        System.out.println();
        String argPreview = args != null ? args.toString() : "";
        if (argPreview.length() > 80) argPreview = argPreview.substring(0, 80) + "...";
        System.out.println(text("[工具] " + name + " → " + argPreview, Ansi.CYAN));
        streamAtLineStart = true;
    }

    @Override
    public void onToolResult(String name, ToolResult result) {
        System.out.println(text("· " + (result.ok ? "" : "× ") + name + ": " + result.preview(), Ansi.GRAY));
        streamAtLineStart = true;
    }

    @Override
    public void onSubAgentStart(String description) {
        System.out.println();
        System.out.println(text("~ 子agent: " + description, Ansi.CYAN + ";" + Ansi.BOLD));
        streamAtLineStart = true;
    }

    @Override
    public void onSubAgentDelta(String delta) {
        System.out.print(text(delta, Ansi.GRAY));
        System.out.flush();
        streamAtLineStart = delta.endsWith("\n");
    }

    @Override
    public void onSubAgentDone(String summary) {
        System.out.println();
        System.out.println(text("~ 子agent完成", Ansi.CYAN));
        streamAtLineStart = true;
    }

    @Override
    public void onStatsLine(String line) {
        printlnStats(line);
    }

    @Override
    public void onError(String message) {
        System.out.println(text("× " + message, Ansi.RED));
        streamAtLineStart = true;
    }

    @Override
    public void onWarning(String message) {
        System.out.println(text("! " + message, Ansi.YELLOW));
        streamAtLineStart = true;
    }
}
