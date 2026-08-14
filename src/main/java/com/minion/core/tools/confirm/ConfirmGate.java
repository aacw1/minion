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

    /** 越界写审批（截图等输出类工具）：与越界读同语义，仅弹窗文案区分，避免"越界读取"误导 */
    public synchronized boolean checkWriteOutside(Tool tool, JsonObject args, String path) {
        return checkOutside(tool, args, path, "越界写入");
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
