package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.AckBattleResultsRequest;
import com.game.proto.AckBattleResultsResponse;
import com.game.proto.GetRecentBattleResultsRequest;
import com.game.proto.GetRecentBattleResultsResponse;
import com.game.proto.GetSyncDigestRequest;
import com.game.proto.MessageContent;
import com.game.proto.RetrieveTemporaryItemsRequest;
import com.game.proto.RetrieveTemporaryItemsResponse;
import com.game.proto.SyncDigestS2C;
import com.game.proto.SyncDigestStatus;
import com.game.proto.SyncDomain;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.battle.BattleFixture;
import com.game.scene.player.Bag;
import com.game.scene.player.BagItem;
import com.game.scene.player.BagType;
import com.game.scene.testing.Contracts;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.table.BagErrorTip;
import com.game.table.CommonErrorTip;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 374 临时背包取回、371/372 近期战斗结果恢复与 253/254 落地同步摘要回归测试。
 */
class TempBagBattleResultSyncDigestTest {

    private static final int SESSION = 11;
    private static final long PLAYER = 3001;

    @Test
    void 临时背包374_参数二选一_单件与全取_满包原子不变_战斗中放行() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(SESSION, PLAYER);
        // 放入两件物品到临时格（10 号可叠加物品 ×3 与 11 号可叠加物品 ×2）
        Bag.AddResult added1 = f.bags.addItems(player, BagType.TEMPORARY, Map.of(10, 3L), Reason.ITEM_AWARD, 1, "");
        Bag.AddResult added2 = f.bags.addItems(player, BagType.TEMPORARY, Map.of(11, 2L), Reason.ITEM_AWARD, 1, "");
        assertThat(added1.ok() && added2.ok()).isTrue();
        long guid10 = added1.written().getFirst().guids().getFirst();
        long guid11 = added2.written().getFirst().guids().getFirst();
        int msgId = Contracts.REGISTRY.requireId("SceneBagClientPlayer", "RetrieveTemporaryItems");

        // 两样都不给 / 都给 → 1005
        assertThat(callRetrieve(f, player, msgId, RetrieveTemporaryItemsRequest.getDefaultInstance()).getErrorMessage().getId())
                .isEqualTo(CommonErrorTip.common_error.kInvalidParameter_VALUE);
        assertThat(callRetrieve(f, player, msgId,
                RetrieveTemporaryItemsRequest.newBuilder().setItemId(guid10).setTakeAll(true).build()).getErrorMessage().getId())
                .isEqualTo(CommonErrorTip.common_error.kInvalidParameter_VALUE);

        // 不存在的 item_id → 6015
        assertThat(callRetrieve(f, player, msgId,
                RetrieveTemporaryItemsRequest.newBuilder().setItemId(999_999L).build()).getErrorMessage().getId())
                .isEqualTo(BagErrorTip.bag_error.kBagTemporaryItemNotFound_VALUE);

        // 战斗中仍允许单件取回（避免结算满包死锁）
        f.fighting(PLAYER, 77L);
        RetrieveTemporaryItemsResponse single = callRetrieve(f, player, msgId,
                RetrieveTemporaryItemsRequest.newBuilder().setItemId(guid10).build());
        assertThat(single.getErrorMessage().getId()).isZero();
        assertThat(player.bags().bag(BagType.INVENTORY).total(10)).isEqualTo(3L);
        assertThat(player.bags().bag(BagType.TEMPORARY).item(guid10)).isNull();
        assertThat(player.bags().bag(BagType.TEMPORARY).item(guid11)).isNotNull();

        // 再次取同一件 → 6015（幂等无副作用）
        assertThat(callRetrieve(f, player, msgId,
                RetrieveTemporaryItemsRequest.newBuilder().setItemId(guid10).build()).getErrorMessage().getId())
                .isEqualTo(BagErrorTip.bag_error.kBagTemporaryItemNotFound_VALUE);

        // 全取剩余 + 再次全取空临时格幂等成功
        RetrieveTemporaryItemsResponse all = callRetrieve(f, player, msgId,
                RetrieveTemporaryItemsRequest.newBuilder().setTakeAll(true).build());
        assertThat(all.getErrorMessage().getId()).isZero();
        assertThat(player.bags().bag(BagType.INVENTORY).total(11)).isEqualTo(2L);
        assertThat(player.bags().bag(BagType.TEMPORARY).itemCount()).isZero();

        RetrieveTemporaryItemsResponse emptyAll = callRetrieve(f, player, msgId,
                RetrieveTemporaryItemsRequest.newBuilder().setTakeAll(true).build());
        assertThat(emptyAll.getErrorMessage().getId()).isZero();
    }

    @Test
    void 近期战斗结果371与372_未落盘隐藏_落盘后降序公开与已读确认_超10条拒1015() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(SESSION, PLAYER);
        int getId = Contracts.REGISTRY.requireId("SceneBattleResultClientPlayer", "GetRecentBattleResults");
        int ackId = Contracts.REGISTRY.requireId("SceneBattleResultClientPlayer", "AckBattleResults");

        // 结算第 1 局（501）
        f.fighting(PLAYER, 501L);
        f.deliver(BattleFixture.settlement(PLAYER, 501L, 100).build());
        f.drain();
        // 存档未确认提交前对客户端隐藏（与 BattleEndS2C 同公开时机）
        GetRecentBattleResultsResponse beforeSave = callGetResults(f, player, getId);
        assertThat(beforeSave.getErrorMessage().getId()).isZero();
        assertThat(beforeSave.getResultsList()).isEmpty();

        // 确认提交第 1 局在线存盘后再打第 2 局（502）并落盘
        assertThat(f.repo.pendingProgress()).isEqualTo(1);
        f.repo.takeProgress().complete(ProgressResult.SAVED);
        f.drain();

        f.fighting(PLAYER, 502L);
        f.deliver(BattleFixture.settlement(PLAYER, 502L, 200).build());
        f.drain();
        assertThat(f.repo.pendingProgress()).isEqualTo(1);
        f.repo.takeProgress().complete(ProgressResult.SAVED);
        f.drain();
        GetRecentBattleResultsResponse afterSave = callGetResults(f, player, getId);
        assertThat(afterSave.getErrorMessage().getId()).isZero();
        assertThat(afterSave.getResultsList()).extracting(com.game.proto.PlayerBattleResult::getBattleId)
                .containsExactly(502L, 501L);
        assertThat(afterSave.getResultsList()).allMatch(r -> !r.getSeen());
        assertThat(afterSave.getResults(0).getCurrenciesList()).singleElement().satisfies(c -> assertThat(c.getAmount()).isEqualTo(200L));

        // 超过 10 条确认 → kArraySizeTooLargeInMessage (1015)
        AckBattleResultsRequest.Builder tooMany = AckBattleResultsRequest.newBuilder();
        for (long i = 1; i <= 11; i++) {
            tooMany.addBattleIds(i);
        }
        assertThat(callAckResults(f, player, ackId, tooMany.build()).getErrorMessage().getId())
                .isEqualTo(CommonErrorTip.common_error.kArraySizeTooLargeInMessage_VALUE);

        // 确认 502 已读，501 保持未读
        assertThat(callAckResults(f, player, ackId,
                AckBattleResultsRequest.newBuilder().addBattleIds(502L).addBattleIds(999_999L).build())
                .getErrorMessage().getId()).isZero();
        GetRecentBattleResultsResponse afterAck = callGetResults(f, player, getId);
        assertThat(afterAck.getResults(0).getBattleId()).isEqualTo(502L);
        assertThat(afterAck.getResults(0).getSeen()).isTrue();
        assertThat(afterAck.getResults(1).getBattleId()).isEqualTo(501L);
        assertThat(afterAck.getResults(1).getSeen()).isFalse();
    }

    @Test
    void 同步摘要253与解析器_正常代次与已知域_未知域跳过_畸形与未就绪回UNAVAILABLE() throws Exception {
        // 空 Hash → OK, generation = 0
        Optional<SyncDigestS2C> empty = SyncDigestParser.parse(Map.of());
        assertThat(empty).isPresent();
        assertThat(empty.get().getStatus()).isEqualTo(SyncDigestStatus.SYNC_DIGEST_STATUS_OK);
        assertThat(empty.get().getGeneration()).isZero();

        // 合法 Hash（含未知域 999 向前兼容跳过）
        Map<String, String> valid = new LinkedHashMap<>();
        valid.put("g", "1700000001000");
        valid.put("1", "7");
        valid.put("6", "42");
        valid.put("999", "future_anything");
        Optional<SyncDigestS2C> parsed = SyncDigestParser.parse(valid);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().getStatus()).isEqualTo(SyncDigestStatus.SYNC_DIGEST_STATUS_OK);
        assertThat(parsed.get().getGeneration()).isEqualTo(1700000001000L);
        assertThat(parsed.get().getEntriesList()).hasSize(2);
        assertThat(parsed.get().getEntries(0).getDomain()).isEqualTo(SyncDomain.SYNC_DOMAIN_FRIEND_LIST);
        assertThat(parsed.get().getEntries(0).getVersion()).isEqualTo(7L);
        assertThat(parsed.get().getEntries(1).getDomain()).isEqualTo(SyncDomain.SYNC_DOMAIN_GUILD);
        assertThat(parsed.get().getEntries(1).getVersion()).isEqualTo(42L);

        // 非空缺 g、g=0、已知域值非法 → 判畸形
        assertThat(SyncDigestParser.parse(Map.of("1", "3"))).isEmpty();
        assertThat(SyncDigestParser.parse(Map.of("g", "0", "1", "3"))).isEmpty();
        assertThat(SyncDigestParser.parse(Map.of("g", "10", "1", "-5"))).isEmpty();

        // 端到端 253 → 254 推送
        BattleFixture f = new BattleFixture();
        f.world.withSyncDigests((pid, cb) -> cb.accept(parsed.get()));
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.sink.clear();
        f.request(player, Contracts.IDS.getSyncDigest(), GetSyncDigestRequest.getDefaultInstance());
        List<MessageContent> pushed = f.pushes(player, Contracts.IDS.notifySyncDigest());
        assertThat(pushed).hasSize(1);
        SyncDigestS2C s2c = SyncDigestS2C.parseFrom(pushed.getFirst().getSerializedMessage());
        assertThat(s2c.getStatus()).isEqualTo(SyncDigestStatus.SYNC_DIGEST_STATUS_OK);
        assertThat(s2c.getGeneration()).isEqualTo(1700000001000L);
    }

    private static RetrieveTemporaryItemsResponse callRetrieve(BattleFixture f, ScenePlayer player, int msgId,
                                                               RetrieveTemporaryItemsRequest req) throws Exception {
        long rid = f.request(player, msgId, req);
        MessageContent reply = f.pushes(player, msgId).stream().filter(m -> m.getId() == rid).findFirst().orElseThrow();
        return RetrieveTemporaryItemsResponse.parseFrom(reply.getSerializedMessage());
    }

    private static GetRecentBattleResultsResponse callGetResults(BattleFixture f, ScenePlayer player, int msgId) throws Exception {
        long rid = f.request(player, msgId, GetRecentBattleResultsRequest.getDefaultInstance());
        MessageContent reply = f.pushes(player, msgId).stream().filter(m -> m.getId() == rid).findFirst().orElseThrow();
        return GetRecentBattleResultsResponse.parseFrom(reply.getSerializedMessage());
    }

    private static AckBattleResultsResponse callAckResults(BattleFixture f, ScenePlayer player, int msgId,
                                                           AckBattleResultsRequest req) throws Exception {
        long rid = f.request(player, msgId, req);
        MessageContent reply = f.pushes(player, msgId).stream().filter(m -> m.getId() == rid).findFirst().orElseThrow();
        return AckBattleResultsResponse.parseFrom(reply.getSerializedMessage());
    }
}
