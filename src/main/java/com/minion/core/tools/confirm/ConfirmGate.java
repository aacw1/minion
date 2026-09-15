package com.minion.core.tools.confirm;

import com.google.gson.JsonObject;
import com.minion.core.config.Config;
import com.minion.core.tools.BashTool;
import com.minion.core.tools.DangerousCommands;
import com.minion.core.tools.Tool;

/** 高危操作确认：跳过开关 / 白名单 / Y-N-W-A 交互 */
public class ConfirmGate {

    private final Config config;
    private final ConfirmUi ui;
    private boolean sessionBypass = false;

    public ConfirmGate(Config config, ConfirmUi ui) {
        this.config = config;
        this.ui = ui;
    }

    /** 返回 true = 放行执行 */
    public synchronized boolean check(Tool tool, JsonObject args) {
        if (!tool.isHighRisk(args)) return true;
        if (sessionBypass || config.confirmSkip()) return true;
        if (isWhitelisted(tool, args)) return true;

        String detail = highRiskDetail(tool, args);
        // ⚠ 在 mintty 默认字体链中渲染为 ?，高危确认用 ASCII 的 !
        ConfirmUi.Decision d = ui.ask("! 高危操作 " + detail);
        if (d == ConfirmUi.Decision.APPROVE) return true;
        if (d == ConfirmUi.Decision.REJECT) return false;
        if (d == ConfirmUi.Decision.APPROVE_WHITELIST) {
            addToWhitelist(tool, args);
            return true;
        }
        if (d == ConfirmUi.Decision.APPROVE_SESSION) {
            sessionBypass = true;
            return true;
        }
        return false;
    }

    /** 越界读审批：开关开或会话放行 → 直接放行；否则弹确认（Y 放行本次 / N 拒绝 / A/W 置位会话放行）。
     *  W 对越界读按会话放行处理、不落持久化白名单（与高危操作的白名单语义区分，YAGNI）。 */
    public synchronized boolean checkReadOutside(Tool tool, JsonObject args, String path) {
        return checkOutside(tool, args, path, "越界读取");
    }

    /** 越界读是否已全局放行（开关开或本会话已放行）：与 checkReadOutside 的放行口径一致，
     *  供「不存在的文件」提示判定使用（已放行则不再提示"访问将被拒绝"，直接报文件不存在） */
    public synchronized boolean readOutsideAllowed() {
        return config.readAllowOutside() || sessionBypass;
    }

    /** 越界写审批（截图等输出类工具）：与越界读同语义，仅弹窗文案区分，避免"越界读取"误导 */
    public synchronized boolean checkWriteOutside(Tool tool, JsonObject args, String path) {
        return checkOutside(tool, args, path, "越界写入");
    }

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

    private synchronized boolean checkOutside(Tool tool, JsonObject args, String path, String label) {
        if (config.readAllowOutside() || sessionBypass) return true;
        String detail = tool.name() + " → " + path;
        ConfirmUi.Decision d = ui.ask("! " + label + " " + detail);
        if (d == ConfirmUi.Decision.APPROVE) return true;
        if (d == ConfirmUi.Decision.REJECT) return false;
        sessionBypass = true; // APPROVE_SESSION / APPROVE_WHITELIST 均会话放行
        return true;
    }

    private boolean isWhitelisted(Tool tool, JsonObject args) {
        String toolName = tool.name().toLowerCase();
        if (config.whitelistTools().contains(toolName)) return true;
        if (tool instanceof BashTool && args.has("command")) {
            String first = DangerousCommands.firstToken(args.get("command").getAsString());
            for (String w : config.whitelistCommands()) {
                if (first.equals(w)) return true;
            }
        }
        return false;
    }

    private void addToWhitelist(Tool tool, JsonObject args) {
        if (tool instanceof BashTool && args.has("command")) {
            config.appendWhitelist("confirm.whitelist.commands",
                    DangerousCommands.firstToken(args.get("command").getAsString()));
        } else {
            config.appendWhitelist("confirm.whitelist.tools", tool.name());
        }
    }

    private String highRiskDetail(Tool tool, JsonObject args) {
        if (tool instanceof BashTool && args.has("command")) {
            return "Bash → " + args.get("command").getAsString();
        }
        if (args.has("path")) {
            return tool.name() + " → " + args.get("path").getAsString();
        }
        return tool.name();
    }
}
