package com.game.rank;
import com.game.api.*;
import com.game.common.deadline.Deadline;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.proto.TipInfoMessage;
import com.game.proto.rank.*;
import com.game.table.CommonErrorTip;
import com.google.protobuf.Message;
import java.util.concurrent.*;
import org.apache.dubbo.config.annotation.DubboService;
@DubboService(group = DubboGroups.RANK, interfaceClass = RankAdminService.class)
public final class RankProvider implements RankAdminService, AutoCloseable {
    private final RankStore store;
    private final ServiceCallSignatures signatures;
    private final ThreadPoolExecutor admin;
    public RankProvider(RankStore store, ServiceCallSignatures signatures) {
        this.store = store;
        this.signatures = signatures;
        admin = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256),
                Thread.ofPlatform().name("rank-admin-", 0).daemon(true).factory(), new ThreadPoolExecutor.AbortPolicy());
    }
    static int error(Throwable failure) {
        if (failure instanceof RankStore.Reject reject)
            return reject.tip;
        if (failure instanceof IllegalArgumentException)
            return CommonErrorTip.common_error.kInvalidParameter_VALUE;
        return CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    }
    @FunctionalInterface
    private interface Work<T> {
        T run(String caller, Deadline deadline) throws Exception;
    }
    @SuppressWarnings("unchecked")
    private <T extends Message> CompletableFuture<T> invoke(String method, Message request, T prototype, Work<T> work) {
        var result = new CompletableFuture<T>();
        Deadline budget = Deadline.after(3500);
        try {
            String caller = signatures.verify(method, request);
            admin.execute(() -> {
                try {
                    result.complete(work.run(caller, budget));
                } catch (Exception failure) {
                    var b = prototype.toBuilder();
                    b.setField(b.getDescriptorForType().findFieldByName("error_message"),
                            TipInfoMessage.newBuilder().setId(error(failure)).build());
                    result.complete((T) b.build());
                }
            });
        } catch (Exception failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }
    public CompletableFuture<OpenRankGenerationResponse> openRankGeneration(OpenRankGenerationRequest r) {
        return invoke(OPEN_METHOD, r, OpenRankGenerationResponse.getDefaultInstance(), (c, d) -> store.open(c, r, d));
    }
    public CompletableFuture<SubmitRankScoresResponse> submitRankScores(SubmitRankScoresRequest r) {
        return invoke(SUBMIT_METHOD, r, SubmitRankScoresResponse.getDefaultInstance(), (c, d) -> store.submit(c, r, d));
    }
    public CompletableFuture<FreezeRankGenerationResponse> freezeRankGeneration(FreezeRankGenerationRequest r) {
        return invoke(FREEZE_METHOD, r, FreezeRankGenerationResponse.getDefaultInstance(), (c, d) -> store.freeze(c, r, d));
    }
    public CompletableFuture<RankAdminResponse> removeRankEntry(RemoveRankEntryRequest r) {
        return invoke(REMOVE_METHOD, r, RankAdminResponse.getDefaultInstance(), (c, d) -> {
            store.remove(c, r, false, d);
            return RankAdminResponse.getDefaultInstance();
        });
    }
    public CompletableFuture<RankAdminResponse> deleteRankGeneration(RemoveRankEntryRequest r) {
        return invoke(DELETE_METHOD, r, RankAdminResponse.getDefaultInstance(), (c, d) -> {
            store.remove(c, r, true, d);
            return RankAdminResponse.getDefaultInstance();
        });
    }
    @Override
    public void close() {
        admin.shutdown();
        try {
            if (!admin.awaitTermination(10, TimeUnit.SECONDS))
                admin.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            admin.shutdownNow();
        }
    }
}
