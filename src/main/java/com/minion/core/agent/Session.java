package com.minion.core.agent;

import com.minion.core.config.Config;
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
    public String workDir;
    public String modelName;
    /** 会话级工作目录(相对路径基准);null/空 = 跟随工作区根 */
    public String cwd;
    public List<Message> messages = new ArrayList<Message>();
    public TodoList todos = new TodoList();
    public UsageTracker usage = new UsageTracker();

    private static final AtomicLong SEQ = new AtomicLong();

    public static Session create(Config config) {
        Session s = new Session();
        // 毫秒 + 自增序号：同毫秒内多次 create 也唯一且递增（SessionStore 按字符串排序，
        // 需要字典序=时间序且 id 不重复，秒级时间戳在测试中同秒碰撞会导致文件互相覆盖）
        long seq = SEQ.incrementAndGet() % 10000L;
        s.id = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date())
                + "-" + String.format("%04d", seq);
        s.createdAt = s.id;
        s.workDir = config.workDir();
        s.modelName = config.modelName();
        return s;
    }

    /** 恢复时用（Task 18） */
    public static Session resume(Config config, String id, String createdAt, String workDir,
                                 String modelName, List<Message> messages) {
        Session s = new Session();
        s.id = id;
        s.createdAt = createdAt;
        s.workDir = workDir;
        s.modelName = modelName;
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
