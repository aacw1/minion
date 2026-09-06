package com.minion.core.tools.plugin;

import com.minion.core.tools.Tool;

import java.util.List;

/**
 * 可插拔工具插件：一个插件 = 设置页「工具」里的一行 = tools.json 里的一个段 = registry 里的一个标签。
 * 新增一种可插拔工具只需实现本接口 + 在 gui/plugin 下加一个配置面板，不动会话装配主干。
 * 实现类不得依赖任何 javafx 类型（GUI 面板通过 ToolPluginManager 读写同一批配置对象）。
 */
public interface ToolPlugin {

    /** tools.json 的段名，也是 ToolRegistry 的插件标签：browser / mysql / postgresql / oracle */
    String id();

    /** 设置页显示名 */
    String displayName();

    /** 设置页状态文案（如「未配置浏览器路径」「当前数据源: prod」） */
    String statusText();

    /** 是否允许勾选「启用」：缺关键配置（浏览器无路径/数据库无数据源）时为 false——先配置后启用 */
    boolean canEnable();

    boolean enabled();

    /** 切换启用；实现内部负责落盘（saver）。生效由 ToolRegistry 的 gate 在取用时判定，无需通知会话 */
    void setEnabled(boolean on);

    /** 产出本插件的工具实例（每会话调用一次；无条件创建，启停交给 gate 过滤） */
    List<Tool> createTools(ToolContext ctx);

    /** 配置保存后钩子：浏览器插件用它重建 Chrome，数据库插件无需动作 */
    default void onConfigChanged() { }
}
