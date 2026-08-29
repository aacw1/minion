package com.minion.core.skills;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 技能合并器：内置列表固定（启动扫一次）+ 项目目录按次实扫，产出会话级不可变快照。
 * 本类自身无缓存：每次 resolve 都实扫目录树（快照不可变，空间之间不可能串台）。
 * 调用频率由上层控制——SessionManager 按工作空间缓存 SkillSet.Result，
 * 只在创建/恢复会话与配置变更后首次取值时走到这里。
 */
public class SkillSet {

    /** 递归扫描深度上限 */
    public static final int MAX_SCAN_DEPTH = 6;
    /** 项目技能数量上限（同时作为合并前扫描截断阈值） */
    public static final int MAX_PROJECT_SKILLS = 200;

    private final List<Skill> globals;

    public SkillSet(List<Skill> globals) {
        this.globals = globals == null
                ? Collections.<Skill>emptyList() : new ArrayList<Skill>(globals);
    }

    /** 合并结果：不可变快照 + 可空告警文案 */
    public static class Result {
        public final List<Skill> skills;
        public final String warning;

        Result(List<Skill> skills, String warning) {
            this.skills = skills;
            this.warning = warning;
        }
    }

    /** projectDir 为空/null → 仅内置（等价无项目技能）；否则项目技能在前、同名顶掉内置 */
    public Result resolve(String projectDir) {
        if (projectDir == null || projectDir.trim().isEmpty()) {
            return new Result(Collections.unmodifiableList(new ArrayList<Skill>(globals)), null);
        }
        SkillManager.ScanResult scan =
                SkillManager.scanTree(Paths.get(projectDir), MAX_SCAN_DEPTH, MAX_PROJECT_SKILLS);
        // 名小写作键：与 SkillTool 的 equalsIgnoreCase 匹配口径一致
        Map<String, Skill> merged = new LinkedHashMap<String, Skill>();
        for (Skill s : scan.skills) merged.put(s.name.toLowerCase(Locale.ROOT), s);
        List<Skill> sortedGlobals = new ArrayList<Skill>(globals);
        sortedGlobals.sort((a, b) -> a.name.compareTo(b.name));
        for (Skill s : sortedGlobals) {
            String key = s.name.toLowerCase(Locale.ROOT);
            if (!merged.containsKey(key)) merged.put(key, s);
        }
        return new Result(Collections.unmodifiableList(new ArrayList<Skill>(merged.values())),
                scan.warning);
    }
}
