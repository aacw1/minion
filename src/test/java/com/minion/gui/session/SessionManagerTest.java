package com.minion.gui.session;

import com.minion.core.agent.Session;
import com.minion.core.config.Config;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceManager;
import com.minion.core.llm.Message;
import com.minion.core.skills.Skill;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.ConfirmUi.Decision;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
}
