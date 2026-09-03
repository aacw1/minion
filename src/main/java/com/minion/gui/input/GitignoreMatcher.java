package com.minion.gui.input;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 工作空间根 .gitignore 简易匹配器（供 @文件补全跳过 git 忽略的目录/文件）。
 *  语义（局限与设计文档一致，仅覆盖常见工程场景）：
 *  - 只读工作空间根目录 .gitignore（嵌套 .gitignore 不支持）；
 *  - ! 反向规则不支持，整行跳过；
 *  - # 注释与空行跳过；
 *  - 尾部 / = 目录专用规则（不匹配文件）；
 *  - 模式含 /（或根锚 / 开头）→ 相对工作空间根全路径匹配；否则匹配任意层级 basename；
 *  - 通配：星号 不含 /、问号 单字符、双星 跨 /（双星后紧跟 / 时为任意层级前缀）；
 *  - 大小写跟随文件系统（Windows 忽略大小写，其余敏感）。
 *  匹配对象约定：relPath 统一用 / 分隔（调用方负责转换）。 */
public final class GitignoreMatcher {

    /** 单条规则：pattern 为 ^...$ 全串正则；dirOnly 尾部 / 只匹配目录；basename 对最后一段匹配 */
    private static final class Rule {
        final Pattern pattern;
        final boolean dirOnly;
        final boolean basename;

        Rule(Pattern pattern, boolean dirOnly, boolean basename) {
            this.pattern = pattern;
            this.dirOnly = dirOnly;
            this.basename = basename;
        }
    }

    private final List<Rule> rules = new ArrayList<Rule>();
    private final boolean caseInsensitive = File.separatorChar == '\\';

    private GitignoreMatcher() { }

    /** 加载工作空间根 .gitignore；不存在或读取失败返回 null（视为无忽略规则） */
    public static GitignoreMatcher load(String workDir) {
        if (workDir == null) return null;
        Path root = Paths.get(workDir).toAbsolutePath().normalize();
        Path gi = root.resolve(".gitignore");
        if (!Files.isReadable(gi)) return null;
        GitignoreMatcher m = new GitignoreMatcher();
        try {
            List<String> lines = Files.readAllLines(gi, StandardCharsets.UTF_8);
            boolean cis = m.caseInsensitive;
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue; // 反向不支持，跳过
                boolean dirOnly = line.endsWith("/");
                if (dirOnly) line = line.substring(0, line.length() - 1);
                boolean anchored = line.startsWith("/");
                if (anchored) line = line.substring(1); // 根锚定：只匹配根相对全路径
                else anchored = line.contains("/");
                if (line.isEmpty()) continue;
                m.rules.add(new Rule(compile(cis ? line.toLowerCase() : line), dirOnly, !anchored));
            }
        } catch (IOException ex) {
            return null;
        }
        return m;
    }

    /** gitignore glob → ^...$ 正则：** = .*（后随 / 时吞入作为任意层级前缀），* = [^/]*，? = [^/] */
    private static Pattern compile(String glob) {
        StringBuilder r = new StringBuilder("^");
        for (int i = 0; i < glob.length(); ) {
            char c = glob.charAt(i);
            if (c == '*') {
                int j = i;
                while (j < glob.length() && glob.charAt(j) == '*') j++;
                boolean dbl = j - i > 1;
                if (dbl) {
                    if (j < glob.length() && glob.charAt(j) == '/') { r.append("(?:.*/)?"); j++; }
                    else r.append(".*");
                } else {
                    r.append("[^/]*");
                }
                i = j;
            } else if (c == '?') {
                r.append("[^/]");
                i++;
            } else {
                r.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        r.append('$');
        return Pattern.compile(r.toString());
    }

    /** 目录是否应整棵跳过（命中任意规则；git 中目录名命中规则即忽略目录本身与内容） */
    public boolean matchesDir(String relPath) {
        return matches(relPath, true);
    }

    /** 文件是否应跳过（目录专用规则不参与文件匹配） */
    public boolean matchesFile(String relPath) {
        return matches(relPath, false);
    }

    private boolean matches(String relPath, boolean isDir) {
        if (relPath == null) return false;
        for (Rule rule : rules) {
            if (isDir ? ruleMatches(rule, relPath) : !rule.dirOnly && ruleMatches(rule, relPath)) return true;
        }
        return false;
    }

    private boolean ruleMatches(Rule rule, String relPath) {
        String target = relPath;
        if (rule.basename) {
            int slash = target.lastIndexOf('/');
            if (slash >= 0) target = target.substring(slash + 1);
        }
        if (caseInsensitive) target = target.toLowerCase();
        return rule.pattern.matcher(target).matches();
    }
}
