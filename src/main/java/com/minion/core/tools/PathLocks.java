package com.minion.core.tools;

import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 进程内按规范化路径串行化的锁（striped）。
 *
 * 为什么需要：AgentLoop 会把同一条消息里的多个 tool_call 先全部 submit 到工具线程池
 * （AgentLoop:661-665，池宽 4），再逐个收结果 —— 收集是顺序的、执行是并行的。
 * 确认流程（ConfirmGate）一旦走快速放行路径（跳过确认开关 / 会话放行 / 白名单），
 * 两次 Edit 的"读-改-写"就会真并行：两次 read 都早于任一 write 时，后写者整份覆盖先写者，
 * 造成静默丢更新（探针实测 4 线程 60 轮，240 处改动仅生效 61 处）。
 *
 * 锁粒度用 striped（固定条数按 hash 取模）而非 Map&lt;Path,Lock&gt;：常驻 GUI 进程不应因
 * 写过的路径数不断累积锁对象。代价是极少数不同路径伪冲突（多串行一次毫秒级写盘），可接受。
 *
 * 注意：只覆盖本进程内并发。跨进程（同一工作区开两个 minion 实例）不在本次范围内，
 * 由 {@link AtomicFiles} 保证"至少不产坏文件"；FileLock 与 ATOMIC_MOVE 冲突（实测
 * Windows 上目标被持锁时 ATOMIC_MOVE 抛 AccessDeniedException），故不引入。
 */
public final class PathLocks {

    /** 工具获取路径锁的最长等待秒数（包内可见：测试注入短超时，避免等待 30 秒） */
    static long WAIT_SECONDS = 30;

    private static final int STRIPES = 256;
    private static final ReentrantLock[] LOCKS = new ReentrantLock[STRIPES];

    static {
        for (int i = 0; i < STRIPES; i++) LOCKS[i] = new ReentrantLock();
    }

    private PathLocks() { }

    /**
     * 取该路径对应的锁（可重入：同线程嵌套加锁不会自死锁）。
     * key 用绝对规范化路径，与 PathsGuard 口径一致；不用 toRealPath —— 目标可能尚不存在，
     * 且 toRealPath 会抛 IOException。
     */
    public static ReentrantLock forPath(Path p) {
        String key = p.toAbsolutePath().normalize().toString();
        return LOCKS[(key.hashCode() & 0x7fffffff) % STRIPES];
    }
}
