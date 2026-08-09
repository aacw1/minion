package com.minion.core.tools.confirm;

public interface ConfirmUi {
    enum Decision { APPROVE, REJECT, APPROVE_WHITELIST, APPROVE_SESSION }

    /** 询问用户；返回决策。由实现方负责渲染提示与读取输入。 */
    Decision ask(String message);
}
