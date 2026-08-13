package com.minion.gui.session;

import com.minion.core.agent.Session;
import com.minion.core.agent.TitleGenerator;
import com.minion.core.config.Config;
import com.minion.core.config.ModelConfig;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceManager;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.LlmClient;
import com.minion.core.llm.Message;
import com.minion.core.skills.Skill;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.ConfirmUi.Decision;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** SessionManager 纯逻辑测试：会话 CRUD、工作空间切换、标题回调（无 JavaFX/网络） */
public class SessionManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final ConfirmUi FAKE_UI = new ConfirmUi() {
        @Override public Decision ask(String message) { return Decision.APPROVE; }
    };

    private SessionManager newManager() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar); // 默认值资源生成外部配置
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ModelManager models = ModelManager.load(jar);
        return new SessionManager(FAKE_UI, config, jar, ws, models,
                new ArrayList<Skill>(), null);
    }

    /** 新建会话：无标题 → titlePending */
    @Test
    public void createSession_titlePendingUntilSend() throws Exception {
        SessionManager m = newManager();
        SessionHandle h = m.createSession(null);
        assertTrue(h.titlePending);
        assertNull(h.title);
        assertEquals(1, m.sessions().size());
    }

    /** 删除会话：从列表移除 */
    @Test
    public void deleteSession_removesFromList() throws Exception {
        SessionManager m = newManager();
        SessionHandle h = m.createSession(null);
        m.deleteSession(h);
        assertEquals(0, m.sessions().size());
    }

    /** 重命名：标题更新 + 回调通知 */
    @Test
    public void renameSession_notifiesListener() throws Exception {
        SessionManager m = newManager();
        final List<String> titles = new ArrayList<String>();
        m.addListener(new SessionManager.Listener() {
            @Override public void onSessionTitleChanged(SessionHandle h) { titles.add(h.title); }
            @Override public void onSessionRunningChanged(SessionHandle h, boolean running) { }
            @Override public void onSessionActivated(SessionHandle h) { }
            @Override public void onWorkspaceChanged() { }
            @Override public void onError(String message) { fail("不应有错误: " + message); }
        });
        SessionHandle h = m.createSession(null);
        m.renameSession(h, "修复登录");
        assertEquals("修复登录", h.title);
        assertEquals("修复登录", titles.get(titles.size() - 1));
    }

    /** 工作空间切换：当前工作空间变化，会话列表按空间隔离 */
    @Test
    public void switchWorkspace_changesContext() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ws.add("projA", tmp.newFolder("a").getPath(), "");
        ws.add("projB", tmp.newFolder("b").getPath(), "");
        ModelManager models = ModelManager.load(jar);
        SessionManager m = new SessionManager(FAKE_UI, config, jar, ws, models,
                new ArrayList<Skill>(), null);
        m.switchWorkspace("projA");
        SessionHandle h = m.createSession(null);
        assertEquals(1, m.sessions().size());
        m.switchWorkspace("projB");
        assertEquals(0, m.sessions().size()); // 每个工作空间独立会话集
        assertEquals("projB", m.workspaces().currentName());
    }

    /** 激活会话：当前会话切换 */
    @Test
    public void activateSession_flipsCurrent() throws Exception {
        SessionManager m = newManager();
        final List<SessionHandle> activated = new ArrayList<SessionHandle>();
        m.addListener(new SessionManager.Listener() {
            @Override public void onSessionTitleChanged(SessionHandle h) { }
            @Override public void onSessionRunningChanged(SessionHandle h, boolean running) { }
            @Override public void onSessionActivated(SessionHandle h) { activated.add(h); }
            @Override public void onWorkspaceChanged() { }
            @Override public void onError(String message) { }
        });
        SessionHandle h1 = m.createSession(null);
        SessionHandle h2 = m.createSession(null);
        m.activateSession(h1);
        m.activateSession(h2);
        assertEquals(2, activated.size());
        assertEquals(h2, m.currentSession());
    }

    /** 会话文件位于 jarDir/session/<工作空间名>/ 下 */
    @Test
    public void sessionFilesInWorkspaceDir() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ModelManager models = ModelManager.load(jar);
        SessionManager m = new SessionManager(FAKE_UI, config, jar, ws, models,
                new ArrayList<Skill>(), null);
        SessionHandle h = m.createSession(null);
        Path f = WorkspaceManager.sessionDirFor(jar, ws.currentName()).resolve(h.id + ".json");
        assertTrue(Files.exists(f));
    }

    /** 启动恢复历史会话：落盘会话进入列表，标题回填且非 titlePending */
    @Test
    public void restore_loadsSessionsFromStore() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ModelManager models = ModelManager.load(jar);
        // 预写一个会话文件（模拟上次运行落盘）
        Path dir = WorkspaceManager.sessionDirFor(jar, ws.currentName());
        Files.createDirectories(dir);
        Session s = Session.create(tmp.newFolder("w").getPath(), "deepseek-v4-flash");
        s.title = "已保存的会话";
        s.messages.add(Message.user("你好"));
        new SessionStore(dir).save(s);

        SessionManager m = new SessionManager(FAKE_UI, config, jar, ws, models,
                new ArrayList<Skill>(), null);
        assertEquals(1, m.sessions().size());
        assertEquals("已保存的会话", m.sessions().get(0).title);
        assertFalse(m.sessions().get(0).titlePending);
    }

    /** 恢复会话：历史消息灌入 EventList（点击即可重放显示；TOOL 跳过） */
    @Test
    public void restore_replaysHistoryIntoEventList() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        Session s = Session.create(".", "deepseek");
        s.title = "历史会话";
        s.messages.add(Message.user("你好"));
        s.messages.add(Message.assistant("你好，我是助手"));
        s.messages.add(Message.toolResult("tc1", "ReadTool", "file content"));
        Path sdir = WorkspaceManager.sessionDirFor(jar, "default");
        Files.createDirectories(sdir);
        new SessionStore(sdir).save(s);
        SessionManager m = new SessionManager(FAKE_UI, config, jar, ws,
                ModelManager.load(jar), new ArrayList<Skill>(), null);
        assertEquals(1, m.sessions().size());
        SessionHandle h = m.sessions().get(0);
        List<EventList.Ev> evs = h.controller.eventList().snapshot();
        assertEquals(2, evs.size());
        assertEquals(EventList.Kind.USER_MESSAGE, evs.get(0).kind);
        assertEquals(EventList.Kind.CONTENT, evs.get(1).kind);
    }

    /** 删除会话：会话文件同步删除（防重启后 restore 复活） */
    @Test
    public void deleteSession_removesSessionFile() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ModelManager models = ModelManager.load(jar);
        SessionManager m = new SessionManager(FAKE_UI, config, jar, ws, models,
                new ArrayList<Skill>(), null);
        SessionHandle h = m.createSession(null);
        Path f = WorkspaceManager.sessionDirFor(jar, ws.currentName()).resolve(h.id + ".json");
        assertTrue(Files.exists(f));
        m.deleteSession(h);
        assertFalse(Files.exists(f));
    }

    /** 新建工作空间：配置落盘 + 会话上下文可建 */
    @Test
    public void addWorkspace_buildsContext() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ModelManager models = ModelManager.load(jar);
        SessionManager m = new SessionManager(FAKE_UI, config, jar, ws, models,
                new ArrayList<Skill>(), null);
        m.addWorkspace("projX", tmp.newFolder("x").getPath(), "");
        m.switchWorkspace("projX");
        SessionHandle h = m.createSession(null);
        assertEquals(1, m.sessions().size());
        assertEquals("projX", m.workspaces().currentName());
    }

    /** 删除工作空间：配置删除 + 会话目录删除 + 当前空间回落 */
    @Test
    public void deleteWorkspace_removesConfigAndDir() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ws.add("projA", tmp.newFolder("a").getPath(), "");
        ws.add("projB", tmp.newFolder("b").getPath(), "");
        ModelManager models = ModelManager.load(jar);
        SessionManager m = new SessionManager(FAKE_UI, config, jar, ws, models,
                new ArrayList<Skill>(), null);
        m.switchWorkspace("projA");
        m.createSession(null);
        Path sessionDir = WorkspaceManager.sessionDirFor(jar, "projA");
        assertTrue(Files.exists(sessionDir));

        m.deleteWorkspace("projA");
        assertNull(ws.get("projA"));
        assertFalse(Files.exists(sessionDir));
        assertNotEquals("projA", m.workspaces().currentName());
        assertEquals(0, m.sessions().size());
    }

    /** 重命名工作空间：配置迁移 + 会话目录迁移 + 会话上下文随新名 */
    @Test
    public void renameWorkspace_migratesSessionDir() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ModelManager models = ModelManager.load(jar);
        SessionManager m = new SessionManager(FAKE_UI, config, jar, ws, models,
                new ArrayList<Skill>(), null);
        m.switchWorkspace("default");
        m.createSession(null);
        Path oldDir = WorkspaceManager.sessionDirFor(jar, "default");
        assertTrue(Files.exists(oldDir));

        m.renameWorkspace("default", "主空间");
        assertNull(ws.get("default"));
        assertNotNull(ws.get("主空间"));
        assertFalse(Files.exists(oldDir));
        assertTrue(Files.exists(WorkspaceManager.sessionDirFor(jar, "主空间")));
        assertEquals("主空间", m.workspaces().currentName());
    }

    /** 删除带运行中会话的工作空间：先终止等退出完成再删目录，退出落盘不复活目录（评审 I-1 回归） */
    @Test
    public void deleteWorkspace_runningSession_noDirResurrection() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ws.add("projA", tmp.newFolder("a").getPath(), "");
        ws.add("projB", tmp.newFolder("b").getPath(), "");
        ModelManager models = ModelManager.load(jar);
        SessionManager m = new SessionManager(FAKE_UI, config, jar, ws, models,
                new ArrayList<Skill>(), null);
        m.switchWorkspace("projA"); // 先切换再注册 listener：只捕获删除触发的通知
        final CountDownLatch wsChanged = new CountDownLatch(1);
        m.addListener(new SessionManager.Listener() {
            @Override public void onSessionTitleChanged(SessionHandle h) { }
            @Override public void onSessionRunningChanged(SessionHandle h, boolean running) { }
            @Override public void onSessionActivated(SessionHandle h) { }
            @Override public void onWorkspaceChanged() { wsChanged.countDown(); }
            @Override public void onError(String message) { fail("不应有错误: " + message); }
        });
        final SessionHandle h = m.createSession(null);
        final Path sessionDir = WorkspaceManager.sessionDirFor(jar, "projA");
        final Path sessionFile = sessionDir.resolve(h.id + ".json");
        final CountDownLatch fakePersistDone = new CountDownLatch(1);
        assertTrue(Files.exists(sessionDir));
        // 模拟运行中会话：AgentLoop 退出路径无条件落盘（persistSession → createDirectories+写文件），
        // 且中断不阻断落盘——旧实现先删目录再终止，落盘必然在目录删除后复活它
        h.running = true;
        h.pool.submit(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(200); } catch (InterruptedException e) { /* 落盘不因中断取消 */ }
                try {
                    Files.createDirectories(sessionDir);
                    Files.write(sessionFile, "{\"resurrect\":true}".getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    fail("模拟退出落盘失败: " + e.getMessage());
                } finally {
                    fakePersistDone.countDown(); // 复活尝试已发生，断言目录前必须等它
                }
            }
        });
        assertTrue(m.deleteWorkspace("projA")); // 有运行中会话：后台终止+删除，不阻塞调用线程
        assertTrue("等待复活尝试完成超时", fakePersistDone.await(5, TimeUnit.SECONDS));
        assertTrue("等待后台终止+删除完成超时", wsChanged.await(5, TimeUnit.SECONDS));
        assertNull(ws.get("projA"));
        assertFalse("会话目录被退出落盘复活", Files.exists(sessionDir));
        assertNotEquals("projA", m.workspaces().currentName());
        assertEquals(0, m.sessions().size());
    }

    /** 资源释放：shutdown 关闭全部会话的 LLM 客户端（okhttp 残留修复） */
    @Test
    public void shutdown_closesAllSessionLlms() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ModelManager models = ModelManager.load(jar);
        SpyManager m = new SpyManager(FAKE_UI, config, jar, ws, models);
        m.createSession(null);
        m.createSession(null);
        assertEquals(2, m.created.size());

        m.shutdown();

        for (FakeLlmClient llm : m.created) {
            assertEquals("shutdown 后会话 LLM 应被关闭", 1, llm.closeCount);
        }
    }

    /** 资源释放：删除会话关闭其 LLM 客户端 */
    @Test
    public void deleteSession_closesItsLlm() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ModelManager models = ModelManager.load(jar);
        SpyManager m = new SpyManager(FAKE_UI, config, jar, ws, models);
        SessionHandle h = m.createSession(null);
        FakeLlmClient llm = m.created.get(0);
        assertEquals(0, llm.closeCount);

        m.deleteSession(h);

        assertEquals("删除会话后其 LLM 应被关闭", 1, llm.closeCount);
    }

    /** 资源释放：删除工作空间关闭其全部会话的 LLM 客户端 */
    @Test
    public void deleteWorkspace_closesSessionLlms() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ws.add("projA", tmp.newFolder("a").getPath(), "");
        ws.add("projB", tmp.newFolder("b").getPath(), "");
        ModelManager models = ModelManager.load(jar);
        SpyManager m = new SpyManager(FAKE_UI, config, jar, ws, models);
        m.switchWorkspace("projA");
        m.createSession(null);
        m.createSession(null);
        assertEquals(2, m.created.size());

        assertTrue(m.deleteWorkspace("projA"));

        for (FakeLlmClient llm : m.created) {
            assertEquals("删除工作空间后其会话 LLM 应被关闭", 1, llm.closeCount);
        }
    }

    /** 需求 8：send 后标题为本地截取的前 20 字（不再走 LLM 摘要） */
    @Test
    public void send_setsLocalTitleSynchronously() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ModelManager models = ModelManager.load(jar);
        SpyManager m = new SpyManager(FAKE_UI, config, jar, ws, models);
        SessionHandle h = m.createSession(null);
        assertTrue(h.titlePending);
        final CountDownLatch titleSet = new CountDownLatch(1);
        m.addListener(new SessionManager.Listener() {
            @Override public void onSessionTitleChanged(SessionHandle h) { titleSet.countDown(); }
            @Override public void onSessionRunningChanged(SessionHandle h, boolean running) { }
            @Override public void onSessionActivated(SessionHandle h) { }
            @Override public void onWorkspaceChanged() { }
            @Override public void onError(String message) { } // FakeLlmClient 无脚本 turn 会抛异常，走 onError，忽略
        });
        String longText = "帮我修复登录问题需要修改三个文件的位置和配置";
        m.send(h, longText);
        assertTrue("标题回调超时", titleSet.await(5, TimeUnit.SECONDS));
        assertFalse(h.titlePending);
        assertTrue(h.title.length() <= TitleGenerator.MAX_TITLE_LEN);
        assertEquals(longText.substring(0, 5), h.title.substring(0, 5));
    }

    /** 需求 13：模型变更 propagate——全部会话换新客户端，旧客户端登记待回收不立即 close，删除时全关 */
    @Test
    public void applyModelChanged_replacesLlmAndRetiresOld() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ModelManager models = ModelManager.load(jar);
        SpyManager m = new SpyManager(FAKE_UI, config, jar, ws, models);
        SessionHandle h1 = m.createSession(null);
        SessionHandle h2 = m.createSession(null);
        FakeLlmClient old1 = m.created.get(0);
        FakeLlmClient old2 = m.created.get(1);

        m.applyModelChanged();

        assertEquals(4, m.created.size()); // 两个会话各新建一个
        assertNotSame(old1, h1.llm);
        assertNotSame(old2, h2.llm);
        assertEquals(0, old1.closeCount); // 旧客户端不立即 close（可能 in-flight）
        assertEquals(0, old2.closeCount);

        m.deleteSession(h1); // 删除：当前 + 待回收全部关闭
        assertEquals(1, old1.closeCount);
        assertEquals(1, ((FakeLlmClient) h1.llm).closeCount);
    }

    /** 行为锁：换模型后跑完一轮（running→false），空闲回收路径关闭旧客户端（防回归——删除 closeRetired 调用会漏关 okhttp） */
    @Test
    public void turnCompletion_reclaimsRetiredLlm() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ModelManager models = ModelManager.load(jar);
        SpyManager m = new SpyManager(FAKE_UI, config, jar, ws, models);
        SessionHandle h = m.createSession(null);
        FakeLlmClient old1 = m.created.get(0);

        m.applyModelChanged(); // 换模型：旧客户端登记待回收，新客户端接管
        FakeLlmClient fresh = (FakeLlmClient) h.llm;
        fresh.addTurn("完成"); // 无脚本时 FakeLlmClient 取 turns.get(-1) 越界，须先出牌
        assertEquals(0, old1.closeCount); // 换模型不立即关闭（可能 in-flight）

        final CountDownLatch idle = new CountDownLatch(1);
        m.addListener(new SessionManager.Listener() {
            @Override public void onSessionTitleChanged(SessionHandle h) { }
            @Override public void onSessionRunningChanged(SessionHandle h, boolean running) {
                if (!running) idle.countDown();
            }
            @Override public void onSessionActivated(SessionHandle h) { }
            @Override public void onWorkspaceChanged() { }
            @Override public void onError(String message) { }
        });
        m.send(h, "继续");
        assertTrue("等待会话空闲超时", idle.await(5, TimeUnit.SECONDS));
        assertFalse(h.running);
        assertEquals("空闲回收：换模型遗留的旧客户端应被关闭", 1, old1.closeCount);
        assertEquals(0, fresh.closeCount); // 当前客户端仍在使用，不得关闭
    }

    /** 工作空间排序转发：顺序变化可查；不发通知（拖拽排序不清空右侧） */
    @Test
    public void moveWorkspace_reordersWithoutNotify() throws Exception {
        SessionManager m = newManager();
        m.addWorkspace("projA", "d:/a", "");
        final int[] notified = new int[] { 0 };
        m.addListener(new SessionManager.Listener() {
            @Override public void onSessionTitleChanged(SessionHandle h) { }
            @Override public void onSessionRunningChanged(SessionHandle h, boolean running) { }
            @Override public void onSessionActivated(SessionHandle h) { }
            @Override public void onWorkspaceChanged() { notified[0]++; }
            @Override public void onError(String message) { fail("不应有错误: " + message); }
        });
        assertTrue(m.moveWorkspace("projA", 0));
        assertEquals("projA", m.workspaces().list().get(0).workSpaceName);
        assertEquals(0, notified[0]); // 不触发 onWorkspaceChanged
        assertFalse(m.moveWorkspace("nope", 0));
    }

    /** 间谍子类：拦截 newLlm 注入 FakeLlmClient（真实 DeepSeekClient 构造不连网但无法断言关闭） */
    private static class SpyManager extends SessionManager {
        final List<FakeLlmClient> created = new ArrayList<FakeLlmClient>();

        SpyManager(ConfirmUi ui, Config config, Path jar, WorkspaceManager ws,
                   ModelManager models) {
            super(ui, config, jar, ws, models, new ArrayList<Skill>(), null);
        }

        @Override
        public LlmClient newLlm(ModelConfig mc) {
            FakeLlmClient f = new FakeLlmClient();
            created.add(f);
            return f;
        }
    }
}
