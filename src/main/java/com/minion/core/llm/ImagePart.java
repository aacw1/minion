package com.minion.core.llm;

import java.util.List;

/** 图片内容块（OpenAI 兼容视觉协议 image_url part）。仅 user 消息使用。 */
public class ImagePart {

    /** 单张图片大小上限（5MB，用户确认） */
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    /** 每条消息最多图片数（用户确认） */
    public static final int MAX_IMAGES = 3;
    /** 单图 token 粗估（压缩阈值估算用；不做像素级精确计算） */
    public static final int IMAGE_TOKENS = 500;

    public String mime;    // image/png 等
    public String base64;  // 纯 base64（不含 data: 前缀）
    public String name;    // 原始文件名（占位展示）

    /** 聊天区占位展示文本：图片：<名> 前缀 + 文本（空格分隔）；无图原样返回 text */
    public static String displayText(List<ImagePart> images, String text) {
        if (images == null || images.isEmpty()) return text;
        StringBuilder sb = new StringBuilder();
        for (ImagePart ip : images) {
            if (ip == null || ip.name == null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append("图片：").append(ip.name);
        }
        if (text != null && !text.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(text);
        }
        return sb.toString();
    }
}
