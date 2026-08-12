package com.minion.cli;

/** 启动信息横幅（GUI 迁移中已无 Config 模型/路径信息，输出固定文字；Task 15 随 CLI 包整体删除） */
public class StartupBanner {

    public static String format() {
        return "minion — 代码开发助手（模型/工作空间配置已迁移至 model.json / workspace.json）";
    }
}
