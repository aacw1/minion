package com.minion.core.tools.ssh;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.minion.core.tools.TruncatedOutput;
import com.minion.core.tools.db.MarkdownTable;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Vector;

/**
 * JSch 封装：连接/认证（密码或私钥）/exec/SFTP 原语，每次新建 Session 用完即关（不落池）。
 * 连接与 socket 读写超时 10s；exec 通道另有命令超时（默认 120s，超时断开并提示可能残留进程）。
 * StrictHostKeyChecking=no：不做 known_hosts 指纹管理（限内网/测试服务器，README 已注明）。
 */
public class SshExecutor {

    /** 连接/认证/socket 读写超时（毫秒）；exec 命令超时由调用方按秒传 */
    public static final int CONNECT_TIMEOUT_MS = 10000;

    /** exec 结果：exitCode + 截断组装后的文本（含截断提示与落盘路径） */
    public static class ExecResult {
        public final int exitCode;
        public final String text;
        ExecResult(int exitCode, String text) {
            this.exitCode = exitCode;
            this.text = text;
        }
    }

    /** 测试连接结果：ok=false 时 message 是可直接展示的失败原因 */
    public static class TestResult {
        public final boolean ok;
        public final String message;
        public final long elapsedMs;

        TestResult(boolean ok, String message, long elapsedMs) {
            this.ok = ok;
            this.message = message;
            this.elapsedMs = elapsedMs;
        }
    }

    /** 建连 + 认证；失败抛 SshOpException（首行文案）。调用方负责 session.disconnect() */
    private Session connect(SshConnection c) throws SshOpException {
        try {
            JSch jsch = new JSch();
            if (SshAuth.isKey(c)) {
                String key = c.privateKeyPath.trim();
                String pass = c.passphrase == null ? "" : c.passphrase;
                try {
                    jsch.addIdentity(key, pass);
                } catch (Exception e) {
                    throw new SshOpException("私钥加载失败（" + key + "）: " + firstLine(e.getMessage()), e);
                }
            }
            Session session = jsch.getSession(c.user.trim(), c.host.trim(), c.port);
            if (!SshAuth.isKey(c)) {
                session.setPassword(c.password == null ? "" : c.password);
            }
            Properties conf = new Properties();
            conf.put("StrictHostKeyChecking", "no");
            session.setConfig(conf);
            session.setTimeout(CONNECT_TIMEOUT_MS);
            session.connect(CONNECT_TIMEOUT_MS);
            return session;
        } catch (SshOpException e) {
            throw e;
        } catch (Exception e) {
            String hostPort = c.host.trim() + ":" + c.port;
            String m = firstLine(e.getMessage());
            if (m.contains("Auth") || m.contains("auth") || m.contains("Publickey") || m.contains("password")) {
                throw new SshOpException("ssh 认证失败（" + hostPort + " / " + c.user.trim()
                        + "）：用户名、密码或私钥不正确", e);
            }
            throw new SshOpException("ssh 连接失败（" + hostPort + "）: " + m, e);
        }
    }

    /** 执行远端命令；stdout/stderr 分开读（stderr 行加 [stderr] 前缀），合并返回；
     *  头尾截断超限落盘 tmpDir（TruncatedOutput 语义）。超时（timeoutSeconds）断开连接
     *  返回错误——远端可能残留孤儿进程（提示不谎报）。 */
    public ExecResult exec(SshConnection c, String command, int timeoutSeconds, Path tmpDir)
            throws SshOpException {
        Session session = connect(c);
        ChannelExec ch = null;
        try {
            ch = (ChannelExec) session.openChannel("exec");
            ch.setCommand(command);
            ch.setInputStream(null);          // 不给远端 stdin：命令不挂起等输入
            ch.connect(CONNECT_TIMEOUT_MS);
            InputStream out = ch.getInputStream();
            InputStream err = ch.getErrStream();
            final TruncatedOutput so = TruncatedOutput.open(tmpDir, "ssh-out");
            final TruncatedOutput se = TruncatedOutput.open(tmpDir, "ssh-err");
            Thread to = readThread(out, so, null);
            Thread te = readThread(err, se, "[stderr] ");
            to.start();
            te.start();
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            while (!ch.isClosed()) {
                if (System.currentTimeMillis() > deadline) {
                    throw new SshOpException("命令超时（" + timeoutSeconds + "s），已断开连接，"
                            + "远端可能残留进程: " + command);
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SshOpException("ssh 执行被中断: " + command);
                }
            }
            int exit = ch.getExitStatus();
            so.close();
            se.close();
            join(te);
            join(to);
            String seText = se.finish();
            String outText = so.finish();
            String merged = seText.isEmpty() ? outText : outText + seText;
            return new ExecResult(exit, merged);
        } catch (SshOpException e) {
            throw e;
        } catch (Exception e) {
            throw new SshOpException("ssh 命令执行失败: " + firstLine(e.getMessage()), e);
        } finally {
            if (ch != null) {
                try { ch.disconnect(); } catch (Exception ignored) { }
            }
            try { session.disconnect(); } catch (Exception ignored) { }
        }
    }

    /** 读流线程：读取并 append 到累积器，prefix 非空时行首加前缀（stderr 标注用） */
    private static Thread readThread(final InputStream in, final TruncatedOutput sink,
                                     final String prefix) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        String s = new String(buf, 0, n, StandardCharsets.UTF_8);
                        if (prefix != null) {
                            s = s.replace("\n", "\n" + prefix);
                            if (!s.startsWith(prefix)) s = prefix + s;
                        }
                        sink.append(s);
                    }
                } catch (Exception ignored) { } finally {
                    sink.close();
                }
            }
        }, "minion-ssh-read");
        t.setDaemon(true);
        return t;
    }

    private static void join(Thread t) {
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** SFTP 会话封装：完成一次操作即关 */
    private interface SftpOp {
        String run(ChannelSftp ch) throws Exception;
    }

    private String sftp(SshConnection c, SftpOp op) throws SshOpException {
        Session session = connect(c);
        ChannelSftp ch = null;
        try {
            ch = (ChannelSftp) session.openChannel("sftp");
            ch.connect(CONNECT_TIMEOUT_MS);
            try {
                return op.run(ch);
            } catch (SftpException e) {
                throw sftpError(c, e);
            }
        } catch (SshOpException e) {
            throw e;
        } catch (Exception e) {
            throw new SshOpException("sftp 连接失败: " + firstLine(e.getMessage()), e);
        } finally {
            if (ch != null) {
                try { ch.disconnect(); } catch (Exception ignored) { }
            }
            try { session.disconnect(); } catch (Exception ignored) { }
        }
    }

    private static SshOpException sftpError(SshConnection c, SftpException e) {
        int id = e.id;
        String m = firstLine(e.getMessage());
        if (id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
            return new SshOpException("远端路径不存在: " + m, e);
        }
        if (id == ChannelSftp.SSH_FX_PERMISSION_DENIED) {
            return new SshOpException("远端权限不足: " + m, e);
        }
        return new SshOpException("sftp 操作失败: " + m, e);
    }

    /** 列目录 → Markdown 表（名称/类型/权限/大小/修改时间）；行数超 500 只渲染前 500 行并提示 */
    public String list(final SshConnection c, final String path) throws SshOpException {
        return sftp(c, new SftpOp() {
            @Override public String run(ChannelSftp ch) throws Exception {
                Vector<?> v = ch.ls(path == null ? "." : path);
                final int MAX_ENTRIES = 500;
                List<String> cols = new ArrayList<String>();
                Collections.addAll(cols, "名称", "类型", "权限", "大小", "修改时间");
                List<List<String>> rows = new ArrayList<List<String>>();
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                for (int i = 0; i < v.size() && i < MAX_ENTRIES; i++) {
                    Object o = v.get(i);
                    if (!(o instanceof ChannelSftp.LsEntry)) continue;
                    ChannelSftp.LsEntry en = (ChannelSftp.LsEntry) o;
                    SftpATTRS a = en.getAttrs();
                    List<String> row = new ArrayList<String>();
                    row.add(en.getFilename());
                    row.add(a.isDir() ? "目录" : (a.isLink() ? "链接" : "文件"));
                    row.add(a.getPermissionsString());
                    row.add(String.valueOf(a.getSize()));
                    row.add(fmt.format(new Date(a.getMTime() * 1000L)));
                    rows.add(row);
                }
                String table = MarkdownTable.render(cols, rows);
                return v.size() > MAX_ENTRIES
                        ? table + "\n（共 " + v.size() + " 项，仅显示前 " + MAX_ENTRIES
                                + " 项，需要精确列表请用 SshExec 执行 ls 并按需过滤）"
                        : table;
            }
        });
    }

    /** 下载：远端 → 本地（调用方已守卫 local）；成功返回文案 */
    public String get(final SshConnection c, final String remote, final Path local)
            throws SshOpException {
        return sftp(c, new SftpOp() {
            @Override public String run(ChannelSftp ch) throws Exception {
                ch.get(remote, local.toAbsolutePath().toString());
                return "已下载 " + remote + " → " + local.toAbsolutePath();
            }
        });
    }

    /** 上传：本地（已守卫）→ 远端；覆盖与否由工具层确认策略负责 */
    public String put(final SshConnection c, final Path local, final String remote)
            throws SshOpException {
        return sftp(c, new SftpOp() {
            @Override public String run(ChannelSftp ch) throws Exception {
                long start = System.currentTimeMillis();
                ch.put(local.toAbsolutePath().toString(), remote);
                long cost = System.currentTimeMillis() - start;
                long size = local.toFile().length();
                return "已上传 " + local.toAbsolutePath() + " → " + remote
                        + "（" + size + " 字节，" + cost + "ms）";
            }
        });
    }

    /** 删除：文件 → rm；空目录 → rmdir；非空目录抛错提示走 exec rm -rf（会弹确认） */
    public String rm(final SshConnection c, final String path) throws SshOpException {
        return sftp(c, new SftpOp() {
            @Override public String run(ChannelSftp ch) throws Exception {
                SftpATTRS a;
                try {
                    a = ch.lstat(path);
                } catch (SftpException e) {
                    if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                        return "远端路径不存在，无需删除: " + path;
                    }
                    throw e;
                }
                if (a.isDir()) {
                    try {
                        ch.rmdir(path);
                        return "已删除远端空目录: " + path;
                    } catch (SftpException e) {
                        // rmdir 只删空目录；失败多半是非空目录（或权限不足）→ 引导走 exec rm -rf
                        String why = firstLine(e.getMessage());
                        boolean denied = why != null && why.toLowerCase().contains("permission");
                        return denied
                                ? "远端权限不足，删除失败: " + path
                                : "删除目录失败: " + path + "（目录非空；递归删除请用 SshExec 执行 rm -rf "
                                        + path + "，会弹确认窗）";
                    }
                }
                ch.rm(path);
                return "已删除远端文件: " + path;
            }
        });
    }

    /** 递归建目录（-p 语义）；已存在静默成功 */
    public String mkdirs(final SshConnection c, final String path) throws SshOpException {
        return sftp(c, new SftpOp() {
            @Override public String run(ChannelSftp ch) throws Exception {
                String p = path == null ? "" : path.trim();
                if (p.isEmpty()) return "路径为空，未创建";
                String norm = p.replace('\\', '/');
                StringBuilder cur = new StringBuilder();
                if (norm.startsWith("/")) cur.append('/');
                for (String seg : norm.split("/")) {
                    if (seg.isEmpty()) continue;
                    cur.append(seg);
                    String probe = cur.toString();
                    try {
                        ch.lstat(probe);   // 已存在则跳过
                    } catch (SftpException e) {
                        if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw e;
                        ch.mkdir(probe);
                    }
                    cur.append('/');
                }
                return "已确保远端目录存在: " + p;
            }
        });
    }

    /** 改名/移动（同一 sftp 会话内）；目标已存在多数服务器会失败（透传文案） */
    public String rename(final SshConnection c, final String src, final String dst)
            throws SshOpException {
        return sftp(c, new SftpOp() {
            @Override public String run(ChannelSftp ch) throws Exception {
                ch.rename(src, dst);
                return "已重命名: " + src + " → " + dst;
            }
        });
    }

    /** 测试连接：连接 + 认证即断（同步阻塞至多 10s）；调用方应在后台线程执行 */
    public TestResult test(SshConnection c) {
        if (c == null) return new TestResult(false, "连接不存在", 0);
        long t0 = System.currentTimeMillis();
        Session session = null;
        try {
            session = connect(c);
            long cost = System.currentTimeMillis() - t0;
            return new TestResult(true, "连接成功（" + c.user.trim() + "@" + c.host.trim()
                    + ":" + c.port + "），耗时 " + cost + "ms", cost);
        } catch (SshOpException e) {
            return new TestResult(false, e.getMessage(), 0);
        } finally {
            if (session != null) {
                try { session.disconnect(); } catch (Exception ignored) { }
            }
        }
    }

    /** 取异常信息首行（jsch 多行堆栈信息压成一行文案，同 DbExecutor.firstLine 口径） */
    static String firstLine(String s) {
        if (s == null) return "未知错误";
        int i = s.indexOf('\n');
        return i >= 0 ? s.substring(0, i) : s;
    }
}
