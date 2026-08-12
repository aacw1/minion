package com.minion.cli;

import org.junit.Test;

import static org.junit.Assert.*;

/** GUI 迁移中 banner 已为固定文字（Task 15 随 CLI 包整体删除，此处仅保编译与全绿） */
public class StartupBannerTest {

    @Test
    public void format_returnsFixedBanner() {
        String s = StartupBanner.format();
        assertTrue(s.contains("minion"));
        assertTrue(s.contains("workspace.json"));
    }
}
