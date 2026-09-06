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
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
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
 * 连接与 socket 读写超时 10s（CONNECT_TIMEOUT_MS）；exec 命令超时由调用方按秒传入，
 * 默认 120s 属工具层（SshExecTool.DEFAULT_TIMEOUT），超时断开连接并把已产出的部分输出
 * 随错误文案返回——远端可能残留进程（提示不谎报）。
 * StrictHostKeyChecking=no：不做 known_hosts 指纹管理（限内网/测试服务器，README「ssh」小节已注明）。
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
     *  头尾截断超限落盘 tmpDir（TruncatedOutput 语义）。超时（timeoutSeconds）先断开连接
     *  再收割已产出的部分输出，随错误文案一并返回——远端可能残留孤儿进程（提示不谎报，
     *  部分输出口径同 BashTool 超时）。 */
    public ExecResult exec(SshConnection c, String command, int timeoutSeconds, Path tmpDir)
            throws SshOpException {
        Session session = connect(c);
        ChannelExec ch = null;
        boolean timedOut = false;
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
                    timedOut = true;
                    // 先断开通道让 reader 读到 EOF → 下面 join 收割已产出输出（否则直接抛
                    // 会把已捕获内容丢在未落盘/未 finish 的累积器里，与 BashTool 超时口径不符）
                    try {
                        ch.disconnect();
                    } catch (Exception ignored) { }
                    break;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SshOpException("ssh 执行被中断: " + command);
                }
            }
            int exit = timedOut ? -1 : ch.getExitStatus();
            // 顺序：先 join 再 close——通道刚关闭时 reader 的末块（≤8KB）可能仍在流里未
            // append，若先把 dumpWriter close 掉，该段进不了落盘文件（>30k 截断时尾部错位）
            join(te);
            join(to);
            so.close();          // 幂等（reader finally 也会关）；保证 finish() 前必已 close
            se.close();
            String seText = se.finish();
            String outText = so.finish();
            String merged = seText.isEmpty() ? outText : outText + seText;
            if (timedOut) {
                throw new SshOpException("命令超时（" + timeoutSeconds + "s），已断开连接，"
                        + "远端可能残留进程: " + command
                        + (merged.isEmpty() ? "" : "\n" + merged));
            }
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

    /** 读流线程：读取并 append 到累积器；prefix 非空时行首加前缀（stderr 标注用）。
     *  解码交给 LineDecoder——多字节字符跨块续读、前缀只在真正行首插入（块边界无伪影）。 */
    private static Thread readThread(final InputStream in, final TruncatedOutput sink,
                                     final String prefix) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                final LineDecoder dec = new LineDecoder(prefix);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        sink.append(dec.feed(buf, n));
                    }
                    sink.append(dec.eof());
                } catch (Exception ignored) { } finally {
                    sink.close();
                }
            }
        }, "minion-ssh-read");
        t.setDaemon(true);
        return t;
    }

    /** exec 流式输出解码器（每流一个，包内可见供离线单测）：UTF-8 跨块续读（8192 字节块
     *  边界切开多字节字符时不产生替换符）+ 行首前缀只在真正行首插入（块边界/CRLF 拆分无伪影）。
     *  流末尾不完整字节序列由 eof() 按替换符收尾（与逐块硬解码的容错口径一致）。
     *  CharsetDecoder 不内部暂存块尾不完整序列（JDK8 实测留在入参 ByteBuffer 未消费），
     *  因此未消费尾巴由本类 carry 到下一块前再解码。 */
    static final class LineDecoder {
        private final CharsetDecoder decoder;
        private final String prefix;   // null = 无前缀（stdout）
        private boolean atLineStart = true;   // 流起点即行首（首块首字符前需补前缀）
        private byte[] carry = new byte[0];   // 上一块尾部未解码完的字节（≤3）

        LineDecoder(String prefix) {
            this.prefix = prefix;
            this.decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
        }

        /** 喂入一段读到的字节（独立于前段），返回可追加文本（含行首前缀）；可返回空串 */
        String feed(byte[] buf, int n) {
            if (n <= 0) return "";
            byte[] all = carry.length == 0 ? buf
                    : concat(carry, buf, n);   // 上次尾巴拼到本块前一起解码
            ByteBuffer in = ByteBuffer.wrap(all);
            String text = drain(in, false);
            keepTail(in);                       // 未消费的不完整尾巴留给下次
            return text;
        }

        /** 流结束：冲洗 carry 中不完整字节序列（替换符收尾），之后不得再 feed */
        String eof() {
            String text = drain(ByteBuffer.wrap(carry), true);
            carry = new byte[0];
            return text;
        }

        private static byte[] concat(byte[] a, byte[] b, int n) {
            byte[] all = new byte[a.length + n];
            System.arraycopy(a, 0, all, 0, a.length);
            System.arraycopy(b, 0, all, a.length, n);
            return all;
        }

        private void keepTail(ByteBuffer in) {
            carry = in.hasRemaining()
                    ? java.util.Arrays.copyOfRange(in.array(), in.position(), in.limit())
                    : new byte[0];
        }

        /** 解码到字符串（循环处理 OVERFLOW；REPLACE 动作下不会抛编码异常）；
         *  结束后 in 中未被消费的仅可能是尾部不完整多字节序列（≤3 字节） */
        private String drain(ByteBuffer in, boolean endOfInput) {
            CharBuffer out = CharBuffer.allocate(Math.max(in.remaining() + 8, 16));
            StringBuilder sb = new StringBuilder(out.capacity());
            while (true) {
                out.clear();
                CoderResult r = decoder.decode(in, out, endOfInput);
                out.flip();
                if (out.hasRemaining()) sb.append(out);
                if (r.isUnderflow()) break;
                if (r.isOverflow()) {
                    out = CharBuffer.allocate(out.capacity() * 2);   // 理论不发生（每字节至多 1 字符）
                    continue;
                }
                try {
                    r.throwException();   // REPLACE 动作下不应触达
                } catch (java.nio.charset.CharacterCodingException e) {
                    throw new IllegalStateException("REPLACE 动作下不应出现编码异常", e);
                }
            }
            return decorate(sb.toString());
        }

        /** 行首前缀：等价于「整段文本在每行行首加 prefix」，块边界不产生中断伪影。
         *  规则：行首 = 流起点，或紧跟在 \n 之后的位置；空行（\n\n 之间）也算一行。
         *  注意：多字节字符未解码完整时 feed 返回空串，本方法直接返回、行首状态保持。 */
        private String decorate(String s) {
            if (s.isEmpty()) return s;
            if (prefix == null) return s;
            StringBuilder sb = new StringBuilder(s.length() + prefix.length() * 2);
            boolean need = atLineStart;   // 本块开头是否正处于行首
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (need) sb.append(prefix);
                need = false;
                sb.append(c);
                if (c == '\n') need = true;
            }
            atLineStart = need;
            return sb.toString();
        }
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
