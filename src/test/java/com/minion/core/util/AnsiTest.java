package com.minion.core.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class AnsiTest {

    @Test
    public void wrap_addsCodes() {
        assertEquals("[2mtext[0m", Ansi.wrap("text", Ansi.DIM));
    }
}
