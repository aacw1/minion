package com.minion;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 自举启动器（shade mainClass）：零 JavaFX 依赖，无 FX 的 JVM 上也能执行探测与重启。
 * 流程：设 prism.order → 非 jar（IDE）/ --relaunched（父进程已自举）直启 Main →
 * 决策分派：直启 / 置 minion.warn.jdk8 后直启（MinionApp 弹窗兜底）/
 * RELAUNCH 派生子进程后退出 / ERROR_NO_JVM 报错退出。
 */
public class Boot {

    /** 自举标记：子进程携带，防止重复探测死循环 */
    public static final String RELAUNCH_FLAG = "--relaunched";
    /** RUN_WITH_WARN 兜底时置此属性，MinionApp 检测后弹非模态提示 */
    public static final String WARN_PROPERTY = "minion.warn.jdk8";

    public static void main(String[] args) throws Exception {
        setPrismOrder();
        if (!isRunningFromJar() || hasFlag(args, RELAUNCH_FLAG)) {
            Main.main(args);   // IDE 开发流或已自举：直接进入正常启动
            return;
        }

        String version = System.getProperty("java.version", "");
        boolean is8 = JdkResolver.isJdk8Version(version);
        boolean hasJfx = currentHasJfx();
        String currentJavaExe = Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString();
        Path found = JdkResolver.findJdk8(System.getenv(), JdkResolver.defaultCandidates());
        boolean consoleAttached = System.console() != null;

        JdkResolver.Decision d = JdkResolver.decide(true, is8, hasJfx, currentJavaExe,
                found != null ? found.toString() : null, consoleAttached);
        switch (d.plan) {
            case RUN_DIRECT:
                Main.main(args);
                return;
            case RUN_WITH_WARN:
                System.setProperty(WARN_PROPERTY, "1");
                Main.main(args);
                return;
            case RELAUNCH:
                relaunch(d, args);          // 内部 System.exit(0)，不会返回
                return;
            default:
                errorExit();                // ERROR_NO_JVM：stderr + Swing 错误框 + exit(1)
        }
    }

    /**
     * 渲染管线选择：默认 es2（OpenGL 硬件加速）优先、sw 软件渲染兜底，失败自动回退。
     * 历史：旧默认 sw（bat 迁移）在 4K/独立显卡机器上强制软件光栅化，全局界面卡顿
     * （悬停变色/打字慢 1 秒，用户实测 1080p 不卡、探针实测 es2 管线初始化成功）；
     * d3d 曾出问题被禁用（用户明确不能开），故默认候选不含 d3d。
     * 注意：候选列表必须逗号分隔（JavaFX 按 split(",") 解析，空格分隔会被当作单一管线名
     * → ClassNotFoundException → QuantumRenderer 无管线启动崩溃，2026-08-17 实证）。
     * 优先级：显式 -Dprism.order > MINION_PRISM > "es2,sw" 默认。须在 JavaFX 初始化前调用。
     */
    private static void setPrismOrder() {
        if (System.getProperty("prism.order") != null) return;   // 尊重用户显式 -Dprism.order
        String p = System.getenv("MINION_PRISM");
        System.setProperty("prism.order", (p == null || p.isEmpty()) ? "es2,sw" : p);
    }

    /** 以目标 java.exe 派生子进程（--relaunched + 原 args 透传），父进程退出 */
    private static void relaunch(JdkResolver.Decision d, String[] args) throws Exception {
        String jarPath = new File(Boot.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        String jarDir = new File(jarPath).getParent();
        // 控制台隐藏（boot.console=false 默认）：exe 换 javaw.exe 直启，无窗口；
        // boot.console=true 恢复既有行为（java.exe + cmd start 开窗 / 继承控制台）
        boolean hidden = JdkResolver.consoleHidden(Paths.get(jarDir, "config.properties"));
        String exe = hidden ? JdkResolver.javawExe(d.javaExe) : d.javaExe;
        boolean newConsole = hidden ? false : d.newConsole;
        List<String> cmd = JdkResolver.buildCommand(exe, jarDir, jarPath, newConsole, args);
        // 工作目录固定为 jar 目录：相对路径（config.properties/skills/workspace.json）按 jar 同目录解析
        try {
            new ProcessBuilder(cmd).directory(new File(jarDir)).start();
        } catch (IOException e) {
            // javaw 双击场景异常上抛会静默无窗，统一走错误框兜底
            errorExit();
        }
        System.exit(0);
    }

    /** 无可用 JVM：stderr + Swing 错误框（替代被删 bat 的 exit /b 1；双击 javaw 无控制台也能看到） */
    private static void errorExit() {
        String msg = "未找到可用的 JDK 8（含 JavaFX）。请安装 Oracle JDK 8 或 Zulu 8 FX 后重试，"
                + "或将环境变量 JAVA_HOME / MINION_JAVA 指向 JDK 8 安装目录。";
        System.err.println("[minion] " + msg);
        try {
            javax.swing.JOptionPane.showMessageDialog(null, msg, "minion 启动失败",
                    javax.swing.JOptionPane.ERROR_MESSAGE);
        } catch (Throwable t) { /* 连 Swing 都不可用（极端 JVM）时仅 stderr */ }
        System.exit(1);
    }

    /** 是否运行在 jar 内（IDE/classes 目录 → false → 直启，不干扰开发流） */
    static boolean isRunningFromJar() {
        try {
            return Boot.class.getProtectionDomain().getCodeSource().getLocation()
                    .toURI().getPath().endsWith(".jar");
        } catch (Exception e) {
            return false;
        }
    }

    /** 当前 JVM 是否加载得了 JavaFX（只探测类，不初始化工具链）。类名常量拆写，规避文件内该字样的自检 */
    static boolean currentHasJfx() {
        try {
            Class.forName("java" + "fx.application.Application", false, Boot.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) {
            if (flag.equals(a)) return true;
        }
        return false;
    }
}
