package com.game.match.testing;

import com.game.common.deadline.Deadline;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试替身共用的故障注入：按「操作名」预置失败，替身在操作的固定位置调 {@link #check}。操作名就是接口的方法名（如 {@code "enqueue"}、{@code "pop"}）；
 * 带 {@code ":after"} 后缀的是「效果已经生效、应答丢了」（结局不明）的注入点，只有写操作有。
 *
 * <pre>
 * store.faults.failNext("pop");            // 下一次 pop 在执行之前失败（什么都没写）
 * store.faults.failNext("pop:after");      // 下一次 pop 执行完再失败（已经弹出，调用方却看到异常）
 * store.faults.failAlways("read");         // 之后每次 read 都失败，直到 clear("read")
 * </pre>
 * 缺省抛 {@link Deadline.DependencyException}（真实现在 Redis 出错 / 超时时抛的就是它）；也可以给任意 RuntimeException。线程安全。
 */
public final class Faults {

    private final Map<String, Deque<RuntimeException>> once = new ConcurrentHashMap<>();
    private final Map<String, RuntimeException> always = new ConcurrentHashMap<>();

    /** 下一次 {@code op} 失败一次（可以叠加多次：每调一次多失败一回）。 */
    public Faults failNext(String op) {
        return failNext(op, new Deadline.DependencyException("注入的故障: " + op));
    }

    public Faults failNext(String op, RuntimeException error) {
        once.computeIfAbsent(op, k -> new ArrayDeque<>()).add(error);
        return this;
    }

    /** 之后每一次 {@code op} 都失败，直到 {@link #clear}。 */
    public Faults failAlways(String op) {
        always.put(op, new Deadline.DependencyException("注入的故障（持续）: " + op));
        return this;
    }

    public Faults clear(String op) {
        once.remove(op);
        always.remove(op);
        return this;
    }

    public Faults clearAll() {
        once.clear();
        always.clear();
        return this;
    }

    /** 替身在注入点调用：有预置的失败就抛出（一次性的用掉一个）。 */
    public void check(String op) {
        Deque<RuntimeException> queue = once.get(op);
        if (queue != null) {
            RuntimeException error;
            synchronized (queue) {
                error = queue.poll();
            }
            if (error != null) {
                throw error;
            }
        }
        RuntimeException persistent = always.get(op);
        if (persistent != null) {
            throw persistent;
        }
    }
}
