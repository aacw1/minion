package com.minion.core.tools.ssh;

/**
 * ssh 连接配置项（tools.json 里 ssh.connections 数组元素）。
 * 字段名即 JSON 键名，gson 直接序列化；密码/passphrase 明文存（与数据源口径一致）。
 * 认证方式互斥由 GUI 表单保证（保存时清空另一组）；手改文件双填时 key 优先（见 SshAuth）。
 */
public class SshConnection {
    /** 标识名：同一插件内唯一，可修改 */
    public String name = "";
    /** 主机：域名/IP（IPv6 允许）；不做格式强校验，兼容内网别名 */
    public String host = "";
    public int port = 22;
    public String user = "";
    /** 认证方式=密码时使用；保存不 trim（首尾空格可能是密码一部分） */
    public String password = "";
    /** 认证方式=私钥时使用；绝对路径或相对工作区路径 */
    public String privateKeyPath = "";
    /** 私钥口令，可空 */
    public String passphrase = "";

    public SshConnection() { }

    public SshConnection(String name, String host, int port, String user,
                         String password, String privateKeyPath, String passphrase) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.privateKeyPath = privateKeyPath;
        this.passphrase = passphrase;
    }

    /** 「prod（root@10.0.0.5:22）」展示用 */
    public String label() {
        return name + "（" + user + "@" + host + ":" + port + "）";
    }
}
