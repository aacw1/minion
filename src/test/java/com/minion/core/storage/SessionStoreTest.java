package com.minion.core.storage;

import com.minion.core.agent.Session;
import com.minion.core.config.Config;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class SessionStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Session makeSession(Config config) {
        Session s = Session.create(config);
        // 顺序对齐断言：0=摘要, 1=assistant(思考过程), 2=assistant(toolCalls), 3=user, 4=toolResult
        com.minion.core.llm.Usage u = new com.minion.core.llm.Usage();
        u.inputTokens = 7;
        u.outputTokens = 3;
        u.reasoningTokens = 2;
        s.usage.record(u); // usage 必须随会话持久化（T21 M6）
        Message sum = Message.user("【摘要】旧历史");
        sum.summary = true;
        s.messages.add(sum);
        Message a = Message.assistant("已分析");
        a.reasoningContent = "思考过程"; // 关键：reasoning 必须持久化
        s.messages.add(a);
        ToolCall tc = new ToolCall();
        tc.id = "c1"; tc.name = "Read"; tc.arguments = "{}";
        Message a2 = Message.assistant(null);
        a2.toolCalls = Collections.singletonList(tc);
        s.messages.add(a2);
        s.messages.add(Message.user("你好"));
        s.messages.add(Message.toolResult("c1", "Read", "内容"));
        return s;
    }

    @Test
    public void saveLoad_roundTrip() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        SessionStore store = new SessionStore(tmp.getRoot().toPath().resolve("sessions"));
        Session s = makeSession(config);
        store.save(s);

        Session loaded = store.load(s.id);
        assertEquals(s.id, loaded.id);
        assertEquals(5, loaded.messages.size());
        assertEquals("思考过程", loaded.messages.get(1).reasoningContent);
        assertEquals("c1", loaded.messages.get(2).toolCalls.get(0).id);
        assertTrue(loaded.messages.get(0).summary);
        assertEquals(Message.Role.TOOL, loaded.messages.get(4).role);
        assertEquals(config.workDir(), loaded.workDir);
        assertEquals(10, loaded.usage.sessionTotal());
        assertEquals(2, loaded.usage.sessionThinking());
    }

    /** S6：目录中单个损坏 .json 文件不影响 list()/latest()，正常条目仍在 */
    @Test
    public void list_skipsCorruptFiles() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        Path dir = tmp.getRoot().toPath().resolve("sessions");
        SessionStore store = new SessionStore(dir);
        Session a = makeSession(config);
        store.save(a);
        // 非法 JSON
        Files.write(dir.resolve("broken.json"),
                "{not-json".getBytes(StandardCharsets.UTF_8));
        // 合法 JSON 但缺 id/createdAt（旧格式/损坏条目）
        Files.write(dir.resolve("noid.json"),
                "{\"messages\":[]}".getBytes(StandardCharsets.UTF_8));
        List<SessionStore.SessionMeta> list = store.list();
        assertEquals(1, list.size());
        assertEquals(a.id, list.get(0).id);
        assertFalse(list.get(0).preview.isEmpty());
        assertEquals(a.id, store.latest().id); // latest() 同样不受损坏文件影响
    }

    @Test
    public void list_sortedByNewest() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        SessionStore store = new SessionStore(tmp.getRoot().toPath().resolve("sessions"));
        Session a = makeSession(config);
        Session b = makeSession(config);
        // 手动保证时间序：写两次
        store.save(a);
        Thread.sleep(1100);
        store.save(b);
        List<SessionStore.SessionMeta> list = store.list();
        assertEquals(2, list.size());
        assertEquals(b.id, list.get(0).id);
        assertEquals(a.id, list.get(1).id);
        assertFalse(list.get(0).preview.isEmpty());
    }

    @Test
    public void latest_returnsMostRecent() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        SessionStore store = new SessionStore(tmp.getRoot().toPath().resolve("sessions"));
        Session a = makeSession(config);
        store.save(a);
        Session loaded = store.latest();
        assertEquals(a.id, loaded.id);
        assertEquals(5, loaded.messages.size());
    }

    /** T4：会话级 cwd 随会话 JSON 持久化（恢复时工作区跟随） */
    @Test
    public void sessionCwdSerialized() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        SessionStore store = new SessionStore(tmp.getRoot().toPath().resolve("sessions"));
        Session s = Session.create(config);
        s.cwd = "/tmp/some/dir";
        store.save(s);
        Session loaded = store.load(s.id);
        assertEquals("/tmp/some/dir", loaded.cwd);
    }

    /** Task 6：/delete 删除会话文件后 load 抛 IOException */
    @Test
    public void deleteRemovesSession() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        Path dir = tmp.getRoot().toPath().resolve("sessions");
        SessionStore store = new SessionStore(dir);
        Session s = makeSession(config);
        store.save(s);
        assertNotNull(store.load(s.id));
        store.delete(s.id);
        assertFalse(Files.exists(dir.resolve(s.id + ".json")));
        try {
            store.load(s.id);
            fail("应抛 IOException");
        } catch (IOException expected) { }
    }

    /** Task 6：删除不存在的会话静默成功（不抛异常） */
    @Test
    public void deleteMissingIdSilent() throws Exception {
        SessionStore store = new SessionStore(tmp.getRoot().toPath().resolve("sessions"));
        store.delete("不存在的会话id");
    }
}
