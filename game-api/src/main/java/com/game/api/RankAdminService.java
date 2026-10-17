package com.game.api;

import com.game.proto.rank.*;
import java.util.concurrent.CompletableFuture;

/**
 * 通用榜内部控制面，group rank。每个board固定producer/source_epoch；来源先停止本代SQL写，
 * 将可靠outbox投至最终cursor，再携该cursor封榜。所有请求还须独立角色HMAC；Dubbo引用retries=0。
 * 传输未知重投必须保留完整业务载荷，仅更新auth；error_message非零不能推进源outbox。
 */
public interface RankAdminService {
    String OPEN_METHOD="/rankpb.RankAdmin/OpenRankGeneration";
    String SUBMIT_METHOD="/rankpb.RankAdmin/SubmitRankScores";
    String FREEZE_METHOD="/rankpb.RankAdmin/FreezeRankGeneration";
    String REMOVE_METHOD="/rankpb.RankAdmin/RemoveRankEntry";
    String DELETE_METHOD="/rankpb.RankAdmin/DeleteRankGeneration";
    CompletableFuture<OpenRankGenerationResponse> openRankGeneration(OpenRankGenerationRequest request);
    CompletableFuture<SubmitRankScoresResponse> submitRankScores(SubmitRankScoresRequest request);
    CompletableFuture<FreezeRankGenerationResponse> freezeRankGeneration(FreezeRankGenerationRequest request);
    CompletableFuture<RankAdminResponse> removeRankEntry(RemoveRankEntryRequest request);
    CompletableFuture<RankAdminResponse> deleteRankGeneration(RemoveRankEntryRequest request);
}
