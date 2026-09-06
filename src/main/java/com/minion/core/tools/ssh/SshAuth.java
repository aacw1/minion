package com.minion.core.tools.ssh;

/** 认证方式推导：privateKeyPath trim 非空 → key；否则 password。互斥持久化由表单保证。 */
public final class SshAuth {

    public static final String PASSWORD = "password";
    public static final String KEY = "key";

    private SshAuth() { }

    /** 推导认证方式：私钥路径 trim 非空 → "key"，否则 "password"（全空连接默认密码） */
    public static String mode(SshConnection c) {
        if (c != null && c.privateKeyPath != null && !c.privateKeyPath.trim().isEmpty()) return KEY;
        return PASSWORD;
    }

    public static boolean isKey(SshConnection c) { return KEY.equals(mode(c)); }

    public static boolean isPassword(SshConnection c) { return PASSWORD.equals(mode(c)); }
}
