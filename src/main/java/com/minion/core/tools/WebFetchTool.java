package com.minion.core.tools;

import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 抓取 URL 并转为纯文本（HTML 剥离）。 */
public class WebFetchTool implements Tool {

    /** 输出文本上限（字符） */
    private static final int MAX_TEXT = 20000;
    /** 原始 HTML 累计读取上限（字节）：读到该量即停止，防止超大响应耗尽内存 */
    private static final int READ_LIMIT = MAX_TEXT * 2;
    /** Content-Length 超过该阈值（字节）直接拒绝，避免无谓下载 */
    private static final long CONTENT_LENGTH_LIMIT = MAX_TEXT * 10L;
    private static final int READ_CHUNK = 8192;
    /** charset 探测段长度(字节):只扫 HTML 头部声明 */
    private static final int CHARSET_PROBE_BYTES = 2048;
    /** meta charset / http-equiv 声明正则 */
    private static final Pattern META_CHARSET = Pattern.compile(
            "<meta[^>]+charset\\s*=\\s*[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTTP_EQUIV_CHARSET = Pattern.compile(
            "content\\s*=\\s*[\"'][^\"']*charset\\s*=\\s*([\\w-]+)", Pattern.CASE_INSENSITIVE);

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    /** true = 放行内网/回环地址（测试用）；默认 false 拦截（SSRF 防护） */
    private final boolean allowPrivateHosts;

    public WebFetchTool() { this(false); }

    public WebFetchTool(boolean allowPrivateHosts) { this.allowPrivateHosts = allowPrivateHosts; }

    @Override
    public String name() { return "WebFetch"; }

    @Override
    public String description() { return "抓取网页并转为纯文本摘要"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("抓取网页",
                new String[]{"url"}, new String[]{"url"});
    }

    @Override
    public ToolResult execute(JsonObject args) throws Exception {
        if (!args.has("url")) return ToolResult.error("缺少 url 参数");
        String url = args.get("url").getAsString();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.error("URL 无效: " + url);
        }
        if (!allowPrivateHosts && isPrivateHost(url)) {
            return ToolResult.error("SSRF 防护：禁止访问内网/回环地址");
        }
        Request request = new Request.Builder().url(url)
                .header("User-Agent", "minion/0.1")
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return ToolResult.error("HTTP " + response.code() + " 获取失败");
            }
            if (response.body() == null) return ToolResult.error("空响应");
            String contentType = response.header("Content-Type");
            if (contentType != null && !contentType.isEmpty()
                    && !contentType.toLowerCase().startsWith("text/")) {
                return ToolResult.error("非文本内容: " + contentType);
            }
            long contentLength = response.body().contentLength();
            if (contentLength > CONTENT_LENGTH_LIMIT) {
                return ToolResult.error("内容过大: " + contentLength + " 字节");
            }
            RawBody raw = readBody(response);
            Charset cs = detectCharset(contentType, raw.bytes);
            String text = stripHtml(new String(raw.bytes, cs));
            if (text.length() > MAX_TEXT || raw.cut) {
                text = text.substring(0, Math.min(MAX_TEXT, text.length()))
                        + "\n... 内容过长已截断";
            }
            return ToolResult.success(text);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null) msg = e.getClass().getSimpleName();
            return ToolResult.error("抓取失败: " + msg);
        }
    }

    /** 分块读取响应体，累计至 READ_LIMIT 即止，避免整段缓冲（关闭 source 即释放连接） */
    private static RawBody readBody(Response response) throws IOException {
        BufferedSource source = response.body().source();
        ByteArrayOutputStream out = new ByteArrayOutputStream(READ_LIMIT);
        byte[] chunk = new byte[READ_CHUNK];
        int n;
        while (out.size() < READ_LIMIT) {
            n = source.read(chunk, 0, chunk.length);
            if (n == -1) break;
            out.write(chunk, 0, n);
        }
        return new RawBody(out.toByteArray(), out.size() >= READ_LIMIT);
    }

    /**
     * 解码字符集:Content-Type 头 charset 优先,其次 HTML meta 声明,均无回退 UTF-8。
     * GBK/GB2312/GB18030 统一映射 GBK。探测段按 ISO-8859-1 解码(字节↔字符 1:1,meta 检测不受解码影响)。
     */
    static Charset detectCharset(String contentType, byte[] head) {
        String cs = charsetFromHeader(contentType);
        if (cs == null) {
            String probe = new String(head, 0, Math.min(head.length, CHARSET_PROBE_BYTES),
                    StandardCharsets.ISO_8859_1);
            cs = charsetFromMeta(probe);
        }
        if (cs == null) return StandardCharsets.UTF_8;
        if (cs.equalsIgnoreCase("GBK") || cs.equalsIgnoreCase("GB2312")
                || cs.equalsIgnoreCase("GB18030")) {
            return charsetOrUtf8("GBK");
        }
        return charsetOrUtf8(cs);
    }

    private static String charsetFromHeader(String contentType) {
        if (contentType == null) return null;
        Matcher m = Pattern.compile("charset\\s*=\\s*[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE)
                .matcher(contentType);
        return m.find() ? m.group(1) : null;
    }

    private static String charsetFromMeta(String head) {
        Matcher m1 = META_CHARSET.matcher(head);
        if (m1.find()) return m1.group(1);
        Matcher m2 = HTTP_EQUIV_CHARSET.matcher(head);
        return m2.find() ? m2.group(1) : null;
    }

    private static Charset charsetOrUtf8(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * SSRF 判定：阻断 loopback（127.0.0.0/8、::1）、link-local（169.254.0.0/16、fe80::/10，
     * 含 metadata 169.254.169.254）、私有网段（10/8、172.16/12、192.168/16）与 localhost 主机名。
     * 主机名解析仅发生在 IP 字面量或 localhost 判定上，正常外网主机名走 InetAddress 判定
     * （与随后 HTTP 请求同源解析，无额外暴露面）。
     */
    static boolean isPrivateHost(String url) {
        String host;
        try {
            host = new URL(url).getHost(); // 不含端口；IPv6 字面量形如 [::1]
        } catch (MalformedURLException e) {
            return false; // 交给后续 URL 校验/HTTP 层处理
        }
        if (host == null || host.isEmpty()) return false;
        if (host.startsWith("[")) host = host.substring(1, host.length() - 1); // 去 IPv6 方括号
        String h = host.toLowerCase();
        if (h.equals("localhost")) return true;
        try {
            InetAddress addr = InetAddress.getByName(h);
            return addr.isAnyLocalAddress() || addr.isLoopbackAddress()
                    || addr.isLinkLocalAddress() || addr.isSiteLocalAddress();
        } catch (UnknownHostException e) {
            return false; // 无法解析的主机名交由 HTTP 层自然失败
        }
    }

    /** 剥离 script/style/标签，压缩空白 */
    static String stripHtml(String html) {
        String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?is)<br\\s*/?>", "\n");
        s = s.replaceAll("(?is)</p>|</h[1-6]>|</li>|</tr>", "\n");
        s = s.replaceAll("(?s)<[^>]+>", " ");
        s = s.replaceAll("&nbsp;", " ").replaceAll("&amp;", "&")
             .replaceAll("&lt;", "<").replaceAll("&gt;", ">")
             .replaceAll("&quot;", "\"");
        String title = "";
        Matcher m = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        if (m.find()) title = m.group(1).trim();
        String body = s.replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n+", "\n").trim();
        return title.isEmpty() ? body : "标题: " + title + "\n\n" + body;
    }

    private static final class RawBody {
        final byte[] bytes;
        final boolean cut;
        RawBody(byte[] bytes, boolean cut) { this.bytes = bytes; this.cut = cut; }
    }
}
