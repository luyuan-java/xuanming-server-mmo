package com.game.guild;

import com.game.api.DubboGroups;
import com.game.api.GuildInternalService;
import com.game.api.proto.ListAppliedAssetOpsSinceRequest;
import com.game.api.proto.ListAppliedAssetOpsSinceResponse;
import com.game.common.deadline.Deadline;
import com.game.guild.cache.GuildCacheInvalidator;
import com.game.guild.cache.InvalidationOp;
import com.game.guild.dispatch.GuildWorkerPool;
import com.game.guild.push.GuildPushes;
import com.game.guild.rank.GuildRanks;
import com.game.guild.service.GuildInternalQueries;
import com.game.guild.store.GuildStore;
import com.game.proto.guild.GuildChangeKind;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * {@link GuildInternalService} 的 Dubbo 提供方（group {@code guild}，与 {@code ClientMessageService} 同一端口；guild-economy-spec §3.6、§7.11、Q7）。
 * 承接回档资产查询与账号注销删角收尾清理（提交后再清缓存、解散摘榜并推送帮众）。
 */
@DubboService(group = DubboGroups.GUILD)
public class GuildInternalServiceProvider implements GuildInternalService {

    private static final Logger log = LoggerFactory.getLogger(GuildInternalServiceProvider.class);

    private final GuildInternalQueries queries;
    private final GuildStore store;
    private final GuildCacheInvalidator invalidator;
    private final GuildRanks ranks;
    private final GuildPushes pushes;
    private final Executor executor;
    private final long budgetMs;

    public GuildInternalServiceProvider(GuildInternalQueries queries) {
        this(queries, null, null, null, null, null, 5_000L);
    }

    @Autowired
    public GuildInternalServiceProvider(GuildInternalQueries queries, GuildStore store,
                                        GuildCacheInvalidator invalidator, GuildRanks ranks, GuildPushes pushes,
                                        @Qualifier("guildWorkerPool") GuildWorkerPool workers, GuildProperties props) {
        this(queries, store, invalidator, ranks, pushes, workers, props.requestBudget().toMillis());
    }

    public GuildInternalServiceProvider(GuildInternalQueries queries, GuildStore store,
                                        GuildCacheInvalidator invalidator, GuildRanks ranks, GuildPushes pushes,
                                        Executor executor, long budgetMs) {
        this.queries = queries;
        this.store = store;
        this.invalidator = invalidator;
        this.ranks = ranks;
        this.pushes = pushes;
        this.executor = executor;
        this.budgetMs = budgetMs;
    }

    @Override
    public CompletableFuture<ListAppliedAssetOpsSinceResponse> listAppliedAssetOpsSince(ListAppliedAssetOpsSinceRequest request) {
        return queries.listAppliedAssetOpsSince(request);
    }

    @Override
    public CompletableFuture<Boolean> purgeRemovedRole(long playerId) {
        if (playerId == 0L) {
            return CompletableFuture.completedFuture(true);
        }
        if (store == null) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Executor exec = executor != null ? executor : Runnable::run;
        try {
            exec.execute(() -> {
                try {
                    Deadline deadline = Deadline.after(Math.max(budgetMs, 5_000L));
                    GuildStore.DeletionOutcome res = store.purgeDeletedPlayer(playerId, System.currentTimeMillis(), deadline);
                    finishAfterCommit(playerId, res, deadline);
                    future.complete(true);
                } catch (RuntimeException e) {
                    log.error("[guild] purgeRemovedRole failed player={} err={}",
                            Long.toUnsignedString(playerId), e.toString());
                    future.complete(false);
                }
            });
        } catch (RuntimeException e) {
            log.error("[guild] purgeRemovedRole dispatch rejected player={}", Long.toUnsignedString(playerId));
            future.complete(false);
        }
        return future;
    }

    private void finishAfterCommit(long playerId, GuildStore.DeletionOutcome res, Deadline deadline) {
        if (res.guildId() == 0L) {
            return;
        }
        if (invalidator != null) {
            InvalidationOp op = switch (res.kind()) {
                case DISBANDED -> InvalidationOp.DISBAND;
                case LEADER_TRANSFERRED -> InvalidationOp.TRANSFER;
                default -> InvalidationOp.LEAVE;
            };
            invalidator.afterCommit(op, res.guildId(), deadline, playerId);
        }
        if (res.kind() == GuildStore.DeletionOutcome.Kind.DISBANDED) {
            if (ranks != null) {
                try {
                    ranks.remove(res.guildId(), res.zoneId(), deadline);
                } catch (RuntimeException e) {
                    log.error("[guild] role purge remove disbanded guild from rank failed guild={} zone={}: {}",
                            Long.toUnsignedString(res.guildId()), Integer.toUnsignedString(res.zoneId()), e.toString());
                }
            }
            return;
        }
        if (pushes != null && !res.remainingMembers().isEmpty()) {
            if (res.kind() == GuildStore.DeletionOutcome.Kind.LEADER_TRANSFERRED && res.newLeaderId() != 0L) {
                pushes.notify(GuildChangeKind.GUILD_CHANGE_KIND_LEADER_TRANSFERRED,
                        res.guildId(), playerId, res.newLeaderId(), res.remainingMembers());
            } else if (res.kind() == GuildStore.DeletionOutcome.Kind.REMOVED) {
                pushes.notify(GuildChangeKind.GUILD_CHANGE_KIND_MEMBER_LEFT,
                        res.guildId(), playerId, playerId, res.remainingMembers());
            }
        }
    }
}
