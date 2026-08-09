package com.minion.core.tools;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 危险命令检测：首 token 小写前缀匹配 */
public class DangerousCommands {

    private static final Set<String> DANGEROUS = new HashSet<String>(Arrays.asList(
            "rm", "del", "rd", "rmdir", "format", "mkfs", "dd",
            "shutdown", "taskkill", "pkill", "killall", "fdisk", "mkfs.ext4"));

    /** 取命令第一个 token（空白分割；剥离引号包装、路径前缀、.exe 扩展名后返回） */
    public static String firstToken(String command) {
        if (command == null) return "";
        String trimmed = command.trim();
        if (trimmed.isEmpty()) return "";
        int i = 0;
        while (i < trimmed.length() && !Character.isWhitespace(trimmed.charAt(i))) i++;
        String token = trimmed.substring(0, i);
        // 剥离引号包装（"rm" / 'rm' 等可绕过首 token 检测）
        if (token.length() >= 2
                && (token.charAt(0) == '"' || token.charAt(0) == '\'')
                && token.charAt(token.length() - 1) == token.charAt(0)) {
            token = token.substring(1, token.length() - 1);
        }
        token = token.toLowerCase();
        // basename 匹配：去 \ 与 / 路径前缀（\rm、/usr/bin/rm）、去 .exe 后缀（rm.exe）
        int backslash = token.lastIndexOf('\\');
        int slash = token.lastIndexOf('/');
        int cut = Math.max(backslash, slash);
        if (cut >= 0) token = token.substring(cut + 1);
        if (token.endsWith(".exe")) token = token.substring(0, token.length() - 4);
        return token;
    }

    public static boolean isDangerous(String command) {
        String token = firstToken(command);
        if (token.isEmpty()) return false;
        for (String d : DANGEROUS) {
            if (token.equals(d) || token.startsWith(d + "/")) return true;
        }
        return false;
    }
}
