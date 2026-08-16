package com.minion;

import com.minion.core.config.Config;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceManager;
import com.minion.core.skills.Skill;
import com.minion.core.skills.SkillManager;
import com.minion.gui.MainWindow;
import com.minion.gui.MinionApp;
import com.minion.gui.confirm.GuiConfirmUi;
import com.minion.gui.session.SessionHandle;
import com.minion.gui.session.SessionManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 切换耗时基准（非测试）：实测 sw 渲染下「切页签 + 事件重放」的 FX 队列排空耗时，
 * 并检测队列是否永远排不完（scheduleCorrect 类重入循环）。数据用副本，不写真实会话。
 */
public class BenchSwitch extends Application {

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.out.println("===== uncaught on [" + t.getName() + "] =====");
            e.printStackTrace(System.out);
        });
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        java.nio.file.Path dir = Paths.get("target/bench-data");
        Config config = Config.load(dir);
        WorkspaceManager workspaces = WorkspaceManager.load(dir);
        ModelManager models = ModelManager.load(dir);
        List<Skill> skills = new SkillManager(config.skillsDir()).scan();
        SessionManager manager = new SessionManager(new GuiConfirmUi(), config, dir,
                workspaces, models, skills, null, null);
        // MainWindow 内 InputView 走 MinionApp.config() 静态注入（Main 启动时赋值），基准需反射补上
        try {
            java.lang.reflect.Field f = MinionApp.class.getDeclaredField("config");
            f.setAccessible(true);
            f.set(null, config);
        } catch (Exception e) {
            throw new IllegalStateException("注入 MinionApp.config 失败", e);
        }
        new MainWindow(stage, manager).show();
        List<SessionHandle> ss = manager.sessions();
        System.out.println("sessions=" + ss.size());
        if (ss.isEmpty()) { Platform.exit(); return; }

        // 等首屏稳定
        Platform.runLater(() -> Platform.runLater(() -> runBench(manager, ss)));
    }

    private void runBench(SessionManager manager, List<SessionHandle> ss) {
        // 切换在 FX 线程执行；哨兵等待必须放后台线程（FX 线程 await 会饿死哨兵）
        Thread awaiter = new Thread(() -> {
            for (int round = 0; round < 3; round++) {
                for (int i = 0; i < ss.size(); i++) {
                    final SessionHandle h = ss.get(i);
                    final long t0 = System.nanoTime();
                    final AtomicBoolean drained = new AtomicBoolean(false);
                    final CountDownLatch latch = new CountDownLatch(1);
                    Platform.runLater(() -> {
                        manager.activateSession(h); // 触发真实 onSessionActivated 链路（runLater 内重放）
                        // 哨兵：它执行时，之前入队的 runLater 已全部执行完
                        Platform.runLater(() -> {
                            drained.set(true);
                            latch.countDown();
                        });
                    });
                    boolean done;
                    try {
                        done = latch.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        done = false;
                    }
                    long ms = (System.nanoTime() - t0) / 1000000;
                    System.out.println("switch[" + round + "][" + i + "] " + h.title
                            + " events=" + h.controller.eventList().size()
                            + " drain=" + ms + "ms sentinel=" + (done ? "ok" : "TIMEOUT(10s)"));
                    if (!done) {
                        System.out.println(">>> 队列未排空，外部 jstack 抓 FX 卡点");
                    }
                }
            }
            Platform.runLater(() -> {
                System.out.println("BENCH DONE");
                Platform.exit();
            });
        }, "bench-await");
        awaiter.setDaemon(true);
        awaiter.start();
    }
}
