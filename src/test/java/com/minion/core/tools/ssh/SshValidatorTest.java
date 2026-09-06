package com.minion.core.tools.ssh;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** 表单校验纯函数：标识名/主机/端口/用户/两种认证的必填分支（不做 IO） */
public class SshValidatorTest {

    private static final List<SshConnection> ALL =
            Arrays.asList(conn("prod"));

    private static SshConnection conn(String name) {
        SshConnection c = new SshConnection();
        c.name = name;
        c.host = "h";
        c.user = "u";
        return c;
    }

    @Test
    public void validPasswordAuth() {
        assertNull(SshValidator.validate("dev", "10.0.0.5", 22, "root",
                "password", "secret", "", ALL, null));
    }

    @Test
    public void validKeyAuthWithEmptyPassphrase() {
        assertNull(SshValidator.validate("dev", "10.0.0.5", 22, "root",
                "key", "", "/home/u/.ssh/id_rsa", ALL, null));
    }

    @Test
    public void nameRules() {
        assertNotNull(SshValidator.validate("", "h", 22, "u", "password", "p", "", ALL, null));
        assertNotNull(SshValidator.validate("  prod  ", "h", 22, "u", "password", "p", "", ALL, null));
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 41; i++) longName.append('x');
        assertNotNull(SshValidator.validate(longName.toString(), "h", 22, "u", "password", "p", "", ALL, null));
        assertNull("改回自身原名不算重复", SshValidator.validate("PROD", "h", 22, "u", "password", "p", "", ALL, "prod"));
    }

    @Test
    public void hostPortUserRules() {
        assertNotNull(SshValidator.validate("n", "", 22, "u", "password", "p", "", ALL, null));
        assertNotNull(SshValidator.validate("n", "h", 0, "u", "password", "p", "", ALL, null));
        assertNotNull(SshValidator.validate("n", "h", 65536, "u", "password", "p", "", ALL, null));
        assertNotNull(SshValidator.validate("n", "h", 22, "", "password", "p", "", ALL, null));
    }

    @Test
    public void authSpecificRequired() {
        assertNotNull("密码认证缺密码", SshValidator.validate("n", "h", 22, "u", "password", "  ", "", ALL, null));
        assertNull("密码认证密码首尾空格保留：trim 判空但原样返回由调用方处理",
                SshValidator.validate("n", "h", 22, "u", "password", " p ", "", ALL, null));
        assertNotNull("私钥认证缺路径", SshValidator.validate("n", "h", 22, "u", "key", "", " ", ALL, null));
    }
}
