package com.minion.core.storage;

import com.google.gson.Gson;
import com.minion.core.agent.Session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 会话落盘：原子写（tmp+rename），列表按时间倒序 */
public class SessionStore {

    private final Gson gson = new Gson();
    private final Path dir;

    public SessionStore(Path dir) { this.dir = dir; }

    public Path save(Session session) throws IOException {
        Files.createDirectories(dir);
        String json = gson.toJson(session);
        Path tmp = dir.resolve(session.id + ".json.tmp");
        Path target = dir.resolve(session.id + ".json");
        Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    public Session load(String id) throws IOException {
        Path f = dir.resolve(id + ".json");
        if (!Files.exists(f)) throw new IOException("会话不存在: " + id);
        String json = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        return gson.fromJson(json, Session.class);
    }

    public List<SessionMeta> list() throws IOException {
        List<SessionMeta> metas = new ArrayList<SessionMeta>();
        if (!Files.isDirectory(dir)) return metas;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : stream) {
                try {
                    Session s = gson.fromJson(
                            new String(Files.readAllBytes(f), StandardCharsets.UTF_8), Session.class);
                    if (s == null || s.id == null || s.createdAt == null) continue; // 损坏条目跳过
                    metas.add(new SessionMeta(s.id, s.createdAt, s.preview()));
                } catch (Exception ignored) {
                    // 单个文件损坏（JsonSyntaxException/JsonParseException 等）不影响整个列表
                }
            }
        }
        metas.sort(Comparator.comparing((SessionMeta m) -> m.createdAt).reversed());
        return metas;
    }

    public Session latest() throws IOException {
        List<SessionMeta> metas = list();
        if (metas.isEmpty()) return null;
        return load(metas.get(0).id);
    }

    public static class SessionMeta {
        public final String id;
        public final String createdAt;
        public final String preview;

        public SessionMeta(String id, String createdAt, String preview) {
            this.id = id;
            this.createdAt = createdAt;
            this.preview = preview;
        }
    }
}
