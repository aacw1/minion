package com.minion.core.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;

/**
 * 模型输出的工具参数（tool_calls.arguments）宽松解析。
 *
 * 线上实证（提问工具高频）：模型偶发在**完整 JSON 值之后**再吐尾随杂讯 —— 多余的右花括号、
 * 中文标点（。，）、markdown 反引号、第二个 JSON 等。JsonParser.parseString 读完第一个值后，
 * 会用一个**非 lenient** 的 reader 再检查「文档是否消费完」，遇到杂讯即抛
 * MalformedJsonException: Use JsonReader.setLenient(true) to accept malformed JSON ...
 * 整段参数被弃用、工具直接失败（错误文案还会误导排查方向）。
 *
 * 本类改为手动 JsonReader：开 lenient（容忍未转义换行/单引号/裸键等模型常见写法），
 * 且只读**第一个完整 JSON 值**、不检查尾部 —— 尾部杂讯丢弃，值内部的真畸形照旧抛异常
 * （交调用方报错给模型重发）。尾部杂讯一律无害：工具参数只需第一个对象的语义。
 */
public final class ToolArguments {

    private ToolArguments() { }

    /**
     * 解析工具参数文本。
     *
     * @param arguments arguments 原文（null/空白视为空对象）
     * @return 参数对象（永不为 null）
     * @throws JsonSyntaxException 值本身无法解析（截断、非法转义等）或不是 JSON 对象
     */
    public static JsonObject parse(String arguments) {
        if (arguments == null || arguments.trim().isEmpty()) return new JsonObject();
        JsonReader reader = new JsonReader(new StringReader(arguments));
        reader.setLenient(true);
        JsonElement e = JsonParser.parseReader(reader); // 只读第一个完整值，尾部杂讯不参与检查
        if (e == null || e.isJsonNull()) return new JsonObject();
        if (!e.isJsonObject()) {
            throw new JsonSyntaxException("工具参数不是 JSON 对象: " + brief(e));
        }
        return e.getAsJsonObject();
    }

    /** 非对象参数的展示截断（防超长数组/字符串撑爆错误消息） */
    private static String brief(JsonElement e) {
        String s = e.toString();
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
