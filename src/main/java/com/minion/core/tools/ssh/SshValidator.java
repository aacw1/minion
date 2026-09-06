package com.minion.core.tools.ssh;

import java.util.List;

/** 连接表单校验（纯函数，无 IO）：标识名/主机/端口/用户名/所选认证的必填分支。
 *  私钥文件存在性不做静态校验：留给测试连接/工具调用时报错（与数据源不校验 URL 可达性同思路）。 */
public final class SshValidator {

    /** 标识名长度上限（同数据源） */
    public static final int NAME_MAX = 40;

    private SshValidator() { }

    /**
     * @param name           表单标识名（未 trim）
     * @param host           主机（未 trim；不做 IP 格式强校验，兼容内网别名）
     * @param port           端口
     * @param user           用户名（未 trim，空白视为空）
     * @param authKind       "password" | "key"（表单单选值）
     * @param password       密码认证的密码（未 trim；判空用 trim，保存保留原样）
     * @param privateKeyPath 私钥认证的路径（未 trim）
     * @param all            已有连接全集
     * @param originalName   修改场景传原标识名（trim+忽略大小写比较，自身不算重复）；新建传 null
     * @return null=通过，否则错误文案
     */
    public static String validate(String name, String host, int port, String user,
                                  String authKind, String password, String privateKeyPath,
                                  List<SshConnection> all, String originalName) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return "标识名不能为空";
        if (n.length() > NAME_MAX) return "标识名过长（≤" + NAME_MAX + " 字符）";
        if (all != null) {
            for (SshConnection c : all) {
                if (c == null || c.name == null) continue;
                String existing = c.name.trim();
                if (existing.equalsIgnoreCase(n)
                        && (originalName == null || !existing.equalsIgnoreCase(originalName.trim()))) {
                    return "标识名已存在：" + n;
                }
            }
        }
        if (host == null || host.trim().isEmpty()) return "主机不能为空";
        if (port < 1 || port > 65535) return "端口必须在 1~65535 之间";
        if (user == null || user.trim().isEmpty()) return "用户名不能为空";
        if (SshAuth.KEY.equals(authKind)) {
            if (privateKeyPath == null || privateKeyPath.trim().isEmpty()) return "私钥路径不能为空";
        } else {
            if (password == null || password.trim().isEmpty()) return "密码不能为空";
        }
        return null;
    }
}
