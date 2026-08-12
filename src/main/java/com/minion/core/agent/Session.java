package com.minion.core.agent;

import com.minion.core.llm.Message;
import com.minion.core.llm.UsageTracker;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 会话状态：消息、任务清单、统计。可整体序列化落盘（字段即 JSON 结构）。 */
public class Session {

    public String id;
    public String createdAt;
    /** 会话标题（GUI 显示用；新建会话由 LLM 摘要生成，恢复旧会话可能为 null） */
    public String title;
    public String workDir;
    public String modelName;
    /** 会话级工作目录(相对路径基准);null/空 = 跟随工作区根 */
    public String cwd;
    public List<Message> messages = new ArrayList<Message>();
    public TodoList todos = new TodoList();
    public UsageTracker usage = new UsageTracker();

    private static final AtomicLong SEQ = new AtomicLong();

    /** 会话 id 生成：毫秒 + 自增序号。同毫秒内多次生成也唯一且递增（SessionStore 按字符串排序，
     *  需要字典序=时间序且 id 不重复，秒级时间戳在测试中同秒碰撞会导致文件互相覆盖）。
     *  create 与 regenerateId 共用同一机制，保证 id 格式始终一致。 */
    private static String generateId() {
        long seq = SEQ.incrementAndGet() % 10000L;
        return new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date())
                + "-" + String.format("%04d", seq);
    }

    public static Session create(String workDir, String modelName) {
        Session s = new Session();
        s.id = generateId();
        s.createdAt = s.id;
        s.workDir = workDir;
        s.modelName = modelName;
        return s;
    }

    /** /new 用：重新生成会话 id/createdAt（机制同 create）。旧 id 会话已随 /new 落盘，
     *  新会话若沿用旧 id，后续自动落盘会覆盖上一个会话文件 */
    public void regenerateId() {
        id = generateId();
        createdAt = id;
    }

    /** 恢复时用（Task 18） */
    public static Session resume(String id, String createdAt, String workDir,
                                 String modelName, String title, List<Message> messages) {
        Session s = new Session();
        s.id = id;
        s.createdAt = createdAt;
        s.workDir = workDir;
        s.modelName = modelName;
        s.title = title;
        s.messages = messages;
        return s;
    }

    /** 历史会话列表展示：时间 + 最后用户消息摘要 */
    public String preview() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.role == Message.Role.USER) {
                String text = m.content == null ? "" : m.content.replace('\n', ' ');
                return text.length() > 50 ? text.substring(0, 50) + "..." : text;
            }
        }
        return "(空会话)";
    }
}
