package com.minion.cli;

import org.junit.Test;

import static org.junit.Assert.*;

public class ReplDispatchTest {

    @Test
    public void isCommand_detectsSlashOnly() {
        assertTrue(Repl.isCommand("/help"));
        assertTrue(Repl.isCommand("/skill review"));
        assertFalse(Repl.isCommand("hello"));
        assertFalse(Repl.isCommand("/"));
        assertFalse(Repl.isCommand(""));
    }
}
