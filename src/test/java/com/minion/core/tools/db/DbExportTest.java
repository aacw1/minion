package com.minion.core.tools.db;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** 大字段原样导出纯函数：阈值判定 / 文件名前缀 / 写盘 / 清单文案 */
public class DbExportTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private static Path onlyFile(Path dir) throws Exception {
        List<Path> files = new ArrayList<Path>();
        DirectoryStream<Path> ds = Files.newDirectoryStream(dir);
        try {
            for (Path p : ds) files.add(p);
        } finally {
            ds.close();
        }
        assertEquals("应只产生一个导出文件", 1, files.size());
        return files.get(0);
    }

    @Test
    public void shouldExport_onlyOverThreshold() {
        assertFalse(DbExport.shouldExport(null));
        assertFalse("恰为阈值不导出", DbExport.shouldExport(repeat('a', 20000)));
        assertTrue(DbExport.shouldExport(repeat('a', 20001)));
    }

    @Test
    public void prefix_cleansIllegalCharsAndFallsBack() {
        assertEquals("db-r2-c3-payload", DbExport.prefix(2, 3, "payload"));
        assertEquals("db-r1-c2-a_b_c", DbExport.prefix(1, 2, "a b|c"));
        assertEquals("db-r1-c1-col1", DbExport.prefix(1, 1, ""));
        assertEquals("db-r1-c1-col1", DbExport.prefix(1, 1, null));
        assertEquals("列名超长截到 40", "db-r1-c1-" + repeat('x', 40), DbExport.prefix(1, 1, repeat('x', 60)));
    }

    @Test
    public void dump_writesRawValueAndListsPath() throws Exception {
        Path dir = tmp.newFolder("t").toPath();
        String raw = "第一行\n第二行|竖线\r\n<br>字面量";  // 换行/竖线/字面 <br> 全部原样保留
        List<DbExport.Entry> pending = new ArrayList<DbExport.Entry>();
        pending.add(new DbExport.Entry(1, 3, "payload", raw, null));
        String hint = DbExport.dump(dir, pending);
        Path f = onlyFile(dir);
        assertEquals("逐字节原样（无转义无截断）", raw,
                new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
        assertTrue("清单含引导语", hint.contains("已原样导出"));
        assertTrue("清单含绝对路径", hint.contains(f.toAbsolutePath().toString()));
        assertTrue("清单含行号与列名", hint.contains("第 1 行 · payload（" + raw.length() + " 字符）"));
    }

    @Test
    public void dump_nullTmpDir_degradesToFailureHint() {
        List<DbExport.Entry> pending = new ArrayList<DbExport.Entry>();
        pending.add(new DbExport.Entry(1, 1, "c", repeat('a', 20001), null));
        String hint = DbExport.dump(null, pending);
        assertTrue(hint, hint.contains("1 个大字段原样导出失败"));
        assertTrue(hint, hint.contains("WHERE/LIMIT"));
    }

    @Test
    public void hint_emptyReturnsEmpty_foldsOverFive_partialFailure() {
        assertEquals("", DbExport.hint(new ArrayList<DbExport.Entry>(), 0));
        List<DbExport.Entry> written = new ArrayList<DbExport.Entry>();
        for (int i = 1; i <= 6; i++) {
            written.add(new DbExport.Entry(i, 1, "c", repeat('a', 20001), "/tmp/f" + i + ".txt"));
        }
        String hint = DbExport.hint(written, 2);
        assertTrue(hint.contains("/tmp/f1.txt"));
        assertTrue(hint.contains("/tmp/f5.txt"));
        assertFalse("第 6 条折叠不列出", hint.contains("/tmp/f6.txt"));
        assertTrue(hint.contains("另有 1 个文件未列出"));
        assertTrue(hint.contains("另有 2 个大字段导出失败"));
    }
}
