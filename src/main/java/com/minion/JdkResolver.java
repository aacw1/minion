package com.minion;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 启动决策纯逻辑（自举启动器 Boot 使用）：JDK8 探测、决策表、派生命令构造。
 * 全部方法无 I/O 副作用（文件存在性判断除外），单测用临时目录伪造。
 */
public final class JdkResolver {

    /** 决策结果 */
    public enum Plan { RUN_DIRECT, RUN_WITH_WARN, RELAUNCH, ERROR_NO_JVM }

    /** 决策结果包装：RELAUNCH 携带目标 java.exe 与是否开新控制台 */
    public static final class Decision {
        public final Plan plan;
        public final String javaExe;
        public final boolean newConsole;

        private Decision(Plan plan, String javaExe, boolean newConsole) {
            this.plan = plan; this.javaExe = javaExe; this.newConsole = newConsole;
        }
        public static Decision runDirect()       { return new Decision(Plan.RUN_DIRECT, null, false); }
        public static Decision runWithWarn()     { return new Decision(Plan.RUN_WITH_WARN, null, false); }
        public static Decision relaunch(String javaExe, boolean newConsole) {
            return new Decision(Plan.RELAUNCH, javaExe, newConsole);
        }
        public static Decision error()           { return new Decision(Plan.ERROR_NO_JVM, null, false); }
    }

    private JdkResolver() { }

    /** 当前 JVM 是否为 JDK 8：java.version 以 "1.8" 开头 */
    public static boolean isJdk8Version(String javaVersion) {
        return javaVersion != null && javaVersion.startsWith("1.8");
    }

    /** 该 JDK 目录（JAVA_HOME 或安装根目录）是否含 JavaFX：jre\lib\ext\jfxrt.jar 存在 */
    public static boolean hasJfx(Path javaHomeOrJdkDir) {
        return Files.exists(javaHomeOrJdkDir.resolve("jre").resolve("lib").resolve("ext").resolve("jfxrt.jar"));
    }

    /**
     * 按 bat 同序探测 JDK8 java.exe：MINION_JAVA（存在即信任，不校验 jfxrt）→
     * JAVA_HOME（含 jfxrt）→ 候选目录（含 jfxrt，首中即停）。未命中返回 null。
     */
    public static Path findJdk8(Map<String, String> env, List<Path> candidateDirs) {
        String explicit = env.get("MINION_JAVA");
        if (explicit != null && !explicit.isEmpty() && Files.exists(Paths.get(explicit))) {
            return Paths.get(explicit);
        }
        String javaHome = env.get("JAVA_HOME");
        if (javaHome != null && !javaHome.isEmpty() && hasJfx(Paths.get(javaHome))) {
            return Paths.get(javaHome).resolve("bin").resolve("java.exe");
        }
        for (Path dir : candidateDirs) {
            if (hasJfx(dir)) return dir.resolve("bin").resolve("java.exe");
        }
        return null;
    }

    /** 启动决策表（与设计文档一致） */
    public static Decision decide(boolean fromJar, boolean currentIsJdk8, boolean currentHasJfx,
                                  String currentJavaExe, String foundJavaExe, boolean consoleAttached) {
        if (!fromJar) return Decision.runDirect();                    // IDE 开发流
        if (currentIsJdk8 && currentHasJfx) {
            return consoleAttached ? Decision.runDirect()             // 终端直启
                    : Decision.relaunch(currentJavaExe, true);        // 双击 javaw → 开新控制台
        }
        if (foundJavaExe != null) {
            return Decision.relaunch(foundJavaExe, !consoleAttached); // 切 JDK8；无控制台才开新窗
        }
        if (currentHasJfx) return Decision.runWithWarn();             // 兜底：能跑则跑 + 弹窗
        return Decision.error();                                      // 无可用 JVM
    }

    /**
     * RELAUNCH 派生命令构造：newConsole=true 走 cmd /c start 开新控制台（cmd 首个引号参数为窗口标题）；
     * false 直接派生（继承当前控制台）。--relaunched 标记防循环，原 args 透传。
     */
    public static List<String> buildCommand(String javaExe, String jarDir, String jarPath,
                                            boolean newConsole, String[] originalArgs) {
        List<String> cmd = new ArrayList<String>();
        if (newConsole) {
            cmd.add("cmd"); cmd.add("/c"); cmd.add("start");
            cmd.add("\"Minion\"");
            cmd.add("/D"); cmd.add(jarDir);
        }
        cmd.add(javaExe);
        cmd.add("-jar"); cmd.add(jarPath);
        cmd.add("--relaunched");
        if (originalArgs != null) {
            for (String a : originalArgs) cmd.add(a);
        }
        return cmd;
    }

    /** 默认候选目录（与 bat 清单一致；%LOCALAPPDATA% 展开） */
    public static List<Path> defaultCandidates() {
        List<Path> list = new ArrayList<Path>();
        list.add(Paths.get("D:\\javame\\jdk1.8"));
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isEmpty()) {
            list.add(Paths.get(local, "Programs", "Zulu", "zulu-8"));
        }
        list.add(Paths.get("C:\\Program Files\\Zulu\\zulu-8"));
        list.add(Paths.get("C:\\Program Files\\Java\\jdk1.8"));
        list.add(Paths.get("C:\\Program Files (x86)\\Java\\jdk1.8"));
        return list;
    }
}
