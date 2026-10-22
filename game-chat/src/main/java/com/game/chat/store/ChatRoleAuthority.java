package com.game.chat.store;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.common.player.PlayerSessionAuthority;
import com.game.discovery.sql.AuthorityJdbc;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/** 原会话的聊天读写共用meta/route/P提交边界；SQL等待只发生在本模块有界工作池。 */
public final class ChatRoleAuthority implements AutoCloseable {
    private final AuthorityJdbc sql;
    private final ThreadPoolExecutor workers;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();

    public ChatRoleAuthority(DataSource source) {
        sql = new AuthorityJdbc(source);
        var sequence = new AtomicInteger();
        workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128), task -> {
            var thread = new Thread(task, "chat-authority-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** 身份只取调用原件；完整route/P先于聊天收据与正文，同SQL提交后才回包。 */
    public <T> CompletableFuture<T> execute(SessionContext original, Deadline deadline,
            AuthorityJdbc.Work<T> action) {
        if (original == null || original.getPlayerId() == 0 || original.getAccount().isEmpty()
                || original.getOwnerRevision() <= 0) {
            return CompletableFuture.failedFuture(new SecurityException("聊天缺少原账号归属身份"));
        }
        var result = new CompletableFuture<T>();
        pending.add(result);
        try {
            workers.execute(() -> {
                try {
                    AuthorityJdbc.check(deadline);
                    T value = sql.tx(deadline, transaction -> {
                        if (result.isCancelled()) throw new RejectedExecutionException("聊天请求已取消");
                        var connection = transaction.connection();
                        int timeout = (int) Math.max(1, Math.min(3, (deadline.remainingMillis() + 999) / 1000));
                        // 聊天权限只属于调用者；私聊目标仍是不透明角色号，删除目标不抹除己方历史。
                        // 共用验证器按meta/route/P锁定并核原账号修订，不另建角色/归属事实。
                        PlayerSessionAuthority.lock(connection, original.getPlayerId(), original.getAccount(),
                                original.getOwnerRevision(), timeout);
                        return action.apply(transaction);
                    });
                    result.complete(value);
                } catch (Exception failure) {
                    result.completeExceptionally(failure);
                } finally {
                    pending.remove(result);
                }
            });
        } catch (RejectedExecutionException full) {
            pending.remove(result);
            result.completeExceptionally(full);
        }
        return result;
    }

    @Override public void close() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        } finally {
            for (var future : pending) future.completeExceptionally(new RejectedExecutionException("聊天服务正在关闭"));
        }
    }
}
