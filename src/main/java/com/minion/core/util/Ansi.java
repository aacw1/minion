package com.minion.core.util;

public class Ansi {
    public static final String DIM = "2";
    public static final String CYAN = "36";
    public static final String GREEN = "32";
    public static final String YELLOW = "33";
    public static final String RED = "31";
    public static final String GRAY = "90";
    public static final String BOLD = "1";
    public static final String ITALIC = "3";

    public static String wrap(String s, String code) {
        return "[" + code + "m" + s + "[0m";
    }
}
