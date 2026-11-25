package com.game.scene.trial;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.engine.TrialJournalRules;
import com.game.player.store.state.PlayerTrialJournalState;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleTrialJournalFacts;
import com.game.proto.ClaimTrialJournalMilestoneRequest;
import com.game.proto.ClaimTrialJournalMilestoneResponse;
import com.game.proto.GetRecentBattleResultsRequest;
import com.game.proto.GetRecentBattleResultsResponse;
import com.game.proto.GetTrialJournalRequest;
import com.game.proto.GetTrialJournalResponse;
import com.game.proto.MessageContent;
import com.game.proto.RedeemBoundArtifactVoucherRequest;
import com.game.proto.RedeemBoundArtifactVoucherResponse;
import com.game.proto.TrialJournalMilestoneView;
import com.game.proto.TrialJournalSnapshot;
import com.game.proto.eBattleOutcome;
import com.game.scene.audit.AssetAudit;
import com.game.scene.battle.BattleFixture;
import com.game.scene.player.BagItem;
import com.game.scene.player.BagType;
import com.game.scene.testing.Contracts;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.scene.world.ScenePlayer;
import com.google.protobuf.ByteString;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 五行试炼志 V2 全三卷、永久位图未知高位保留、卷三赛季隔离、M901 专属形象与 456–458 接口回归测试。 */
class TrialJournalFeatureAndServiceTest {

    private static final String SERVICE = "SceneTrialJournalClientPlayer";

    private static void alignActiveSeason(BattleFixture f) {
        var firstEnabled = BattleFixture.CONFIG.seasonRule().all().stream()
                .filter(com.game.table.SeasonRuleTable::getEnabled).findFirst().orElseThrow();
        f.clock.setEpochMillis(firstEnabled.getStartsAtMs() + 5_000L);
    }

    private static <T extends com.google.protobuf.Message> T rpc(
            BattleFixture f, ScenePlayer p, String service, String method, com.google.protobuf.Message req,
            com.google.protobuf.Parser<T> parser) throws Exception {
        int id = Contracts.REGISTRY.requireId(service, method);
        long reqId = f.request(p, id, req);
        MessageContent reply = f.pushes(p, id).stream()
                .filter(m -> m.getId() == reqId).findFirst().orElseThrow();
        return parser.parseFrom(reply.getSerializedMessage());
    }

    @Test
    void 全三卷快照_战斗结算推进战法印与单人合战纪录_并回填PlayerBattleResult21至24() throws Exception {
        BattleFixture f = new BattleFixture();
        alignActiveSeason(f);
        ScenePlayer p = f.enter(11, 2001L);

        // 1. 初始快照：3 卷全部可见，永久分母 27 枚（含 V2 双卫同阵 1051..1053），未通普通首领时困难未解锁
        GetTrialJournalResponse initResp = rpc(f, p, SERVICE, "GetTrialJournal",
                GetTrialJournalRequest.getDefaultInstance(), GetTrialJournalResponse.parser());
        assertThat(initResp.getErrorMessage().getId()).isZero();
        TrialJournalSnapshot initSnap = initResp.getSnapshot();
        assertThat(initSnap.getAvailable()).isTrue();
        assertThat(initSnap.getTotalPermanentSeals()).isEqualTo(27);
        assertThat(initSnap.getTotalPermanentLitSeals()).isZero();
        assertThat(initSnap.getHardBossTrialUnlocked()).isFalse();
        assertThat(initSnap.getChaptersList()).hasSize(3);
        assertThat(initSnap.getRedeemableArtifactsList()).hasSize(8);

        // 预设高位未识别 bit（字节索引 10 置 0x80），验证点亮新印后高位完好保留
        byte[] withHighBit = new byte[11];
        withHighBit[10] = (byte) 0x80;
        p.trialJournal().replace(PlayerTrialJournalState.newBuilder()
                .setLitSealBits(ByteString.copyFrom(withHighBit)).build());

        // 2. 单人速通 101（普通回春，5 回合、零倒地、零护卫治疗）：点亮 1011/1012/1013 + 刷新单人最快 + 解锁困难
        f.fighting(p.playerId(), 90001L);
        BattleSettlementData soloWin = BattleFixture.settlement(p.playerId(), 90001L, 0L)
                .setBattleConfigId(101)
                .setMatchMode(4)
                .setTotalRounds(5)
                .setBossTrialKind(1)
                .setBattleDifficulty(1)
                .setFinishedAtMs(f.clock.epochMillis())
                .setTrialFacts(BattleTrialJournalFacts.newBuilder()
                        .setJournalEntryId(101)
                        .setTrialRuleVersion(1)
                        .setSeasonId(0)
                        .setInitialHumanCount(1)
                        .setSelfOrPetEverDied(false)
                        .setBossGuardHealTotal(0L)
                        .setUsedPetTableId(1)
                        .addUsedArtifactEffectIds(5)
                        .addUsedArtifactEffectIds(1)
                        .setUsedSeasonSealId(101))
                .build();
        f.deliver(soloWin);
        f.drain();
        f.completeSaves(ProgressResult.SAVED);

        // 核对高位未识别 bit 完整保留，且 1011/1012/1013（bit 0..2）已点亮
        ByteString bitsAfter = p.trialJournal().state().getLitSealBits();
        assertThat(bitsAfter.size()).isEqualTo(11);
        assertThat(bitsAfter.byteAt(10) & 0xFF).isEqualTo(0x80);
        assertThat(bitsAfter.byteAt(0) & 0x07).isEqualTo(0x07);

        // 核对 PlayerBattleResult 字段 21–24 落盘可见
        GetRecentBattleResultsResponse recent = rpc(f, p, "SceneBattleResultClientPlayer", "GetRecentBattleResults",
                GetRecentBattleResultsRequest.getDefaultInstance(), GetRecentBattleResultsResponse.parser());
        assertThat(recent.getResultsList()).hasSize(1);
        var res = recent.getResults(0);
        assertThat(res.getTrialJournalEntryId()).isEqualTo(101);
        assertThat(res.getTrialNewlyLitSealIdsList()).containsExactly(1011, 1012, 1013);
        assertThat(res.getTrialNewSoloBest()).isTrue();
        assertThat(res.getTrialNewCoopBest()).isFalse();

        // 3. 好友合战 4 回合：刷新合战最快、不覆写单人最快，并记队友名
        f.fighting(p.playerId(), 90002L);
        BattleSettlementData coopWin = BattleFixture.settlement(p.playerId(), 90002L, 0L)
                .setBattleConfigId(101)
                .setMatchMode(5)
                .setTotalRounds(4)
                .setBossTrialKind(1)
                .setBattleDifficulty(1)
                .setTrialFacts(BattleTrialJournalFacts.newBuilder()
                        .setJournalEntryId(101)
                        .setTrialRuleVersion(1)
                        .setSeasonId(0)
                        .setInitialHumanCount(2)
                        .addTeammateNames("明心道友")
                        .setUsedPetTableId(2))
                .build();
        f.deliver(coopWin);
        f.drain();
        f.completeSaves(ProgressResult.SAVED);

        GetTrialJournalResponse afterResp = rpc(f, p, SERVICE, "GetTrialJournal",
                GetTrialJournalRequest.getDefaultInstance(), GetTrialJournalResponse.parser());
        TrialJournalSnapshot snap = afterResp.getSnapshot();
        assertThat(snap.getHardBossTrialUnlocked()).isTrue();
        assertThat(snap.getTotalPermanentLitSeals()).isEqualTo(3);
        var entry101 = snap.getChapters(0).getEntriesList().stream()
                .filter(e -> e.getEntryId() == 101).findFirst().orElseThrow();
        assertThat(entry101.getSoloRecorded()).isTrue();
        assertThat(entry101.getSoloBest().getBestRounds()).isEqualTo(5);
        assertThat(entry101.getSoloBest().getLoadout().getArtifactEffectIdsList()).containsExactly(1, 5);
        assertThat(entry101.getCoopRecorded()).isTrue();
        assertThat(entry101.getCoopBest().getBestRounds()).isEqualTo(4);
        assertThat(entry101.getCoopBest().getTeammateNamesList()).containsExactly("明心道友");

        // 4. 卷三赛季隔离：正确当前赛季写入 season_records，不污染 lit_seal_bits；跨季迟到结算被拒
        int activeSeason = f.season.activeSeasonId();
        f.fighting(p.playerId(), 90003L);
        BattleSettlementData wrongSeason = BattleFixture.settlement(p.playerId(), 90003L, 0L)
                .setBattleConfigId(109).setMatchMode(4).setTotalRounds(6).setUseFreePreset(true)
                .setTrialFacts(BattleTrialJournalFacts.newBuilder()
                        .setJournalEntryId(301).setTrialRuleVersion(1).setSeasonId(activeSeason + 9)
                        .setInitialHumanCount(1).setGuardSurvived(true))
                .build();
        f.deliver(wrongSeason);
        f.drain();
        f.completeSaves(ProgressResult.SAVED);
        assertThat(p.trialJournal().state().getSeasonRecordsList()).isEmpty();

        f.fighting(p.playerId(), 90004L);
        BattleSettlementData rightSeason = BattleFixture.settlement(p.playerId(), 90004L, 0L)
                .setBattleConfigId(109).setMatchMode(4).setTotalRounds(6).setUseFreePreset(true)
                .setTrialFacts(BattleTrialJournalFacts.newBuilder()
                        .setJournalEntryId(301).setTrialRuleVersion(1).setSeasonId(activeSeason)
                        .setInitialHumanCount(1).setGuardSurvived(true).setSelfOrPetEverDied(false))
                .build();
        f.deliver(rightSeason);
        f.drain();
        f.completeSaves(ProgressResult.SAVED);
        assertThat(p.trialJournal().state().getSeasonRecordsList()).hasSize(1);
        assertThat(p.trialJournal().state().getSeasonRecords(0).getLitSealIdsList()).containsExactly(3011, 3012);
        // 永久位图保持原字节（只含 1011..1013 + 高位 0x80）
        assertThat(p.trialJournal().state().getLitSealBits().byteAt(3)).isZero();
    }

    @Test
    void 里程碑457发绑定道具_M901授专属形象_拓印458兑换仅自用法宝含满包回滚与幂等重放() throws Exception {
        BattleFixture f = new BattleFixture();
        alignActiveSeason(f);
        ScenePlayer p = f.enter(11, 2002L);

        // 点亮前 18 枚永久战法印（bit 0..17），同时满足 M101..M104、M201..M203、M901（18/24 终极形象）
        byte[] eighteen = new byte[] {(byte) 0xFF, (byte) 0xFF, 0x03};
        p.trialJournal().replace(PlayerTrialJournalState.newBuilder()
                .setLitSealBits(ByteString.copyFrom(eighteen)).build());

        // 1. 领取 M901：授予专属形象 20_star_formation_master_girl，并支持幂等重放与参数不符拒绝
        ClaimTrialJournalMilestoneRequest m901Req = ClaimTrialJournalMilestoneRequest.newBuilder()
                .setClientRequestId(5001L).setMilestoneId(901).build();
        ClaimTrialJournalMilestoneResponse m901Resp = rpc(f, p, SERVICE, "ClaimTrialJournalMilestone",
                m901Req, ClaimTrialJournalMilestoneResponse.parser());
        assertThat(m901Resp.getErrorMessage().getId()).isZero();
        assertThat(m901Resp.getReplayed()).isFalse();
        assertThat(m901Resp.getGrantedAppearanceId()).isEqualTo("20_star_formation_master_girl");
        TrialJournalMilestoneView m901View = m901Resp.getSnapshot().getMilestonesList().stream()
                .filter(m -> m.getMilestoneId() == 901).findFirst().orElseThrow();
        assertThat(m901View.getClaimed()).isTrue();
        assertThat(m901View.getClaimable()).isFalse();

        // 幂等重放返回完全相同结果
        ClaimTrialJournalMilestoneResponse m901Replay = rpc(f, p, SERVICE, "ClaimTrialJournalMilestone",
                m901Req, ClaimTrialJournalMilestoneResponse.parser());
        assertThat(m901Replay.getErrorMessage().getId()).isZero();
        assertThat(m901Replay.getReplayed()).isTrue();
        assertThat(m901Replay.getGrantedAppearanceId()).isEqualTo("20_star_formation_master_girl");

        // 同 client_request_id 不同参数回 1005
        ClaimTrialJournalMilestoneResponse m901Mismatch = rpc(f, p, SERVICE, "ClaimTrialJournalMilestone",
                ClaimTrialJournalMilestoneRequest.newBuilder().setClientRequestId(5001L).setMilestoneId(101).build(),
                ClaimTrialJournalMilestoneResponse.parser());
        assertThat(m901Mismatch.getErrorMessage().getId()).isEqualTo(TrialJournalService.INVALID_PARAMETER);

        // 新 client_request_id 重复领 M901 回 ALREADY_CLAIMED
        ClaimTrialJournalMilestoneResponse m901Dup = rpc(f, p, SERVICE, "ClaimTrialJournalMilestone",
                ClaimTrialJournalMilestoneRequest.newBuilder().setClientRequestId(5002L).setMilestoneId(901).build(),
                ClaimTrialJournalMilestoneResponse.parser());
        assertThat(m901Dup.getErrorMessage().getId()).isEqualTo(TrialJournalService.MILESTONE_ALREADY_CLAIMED);

        // 2. 领取含自用法宝拓印的里程碑（从表中找第一条 bound_artifact_voucher_count > 0 的永久里程碑）
        var voucherMilestone = f.trialJournalTables.milestones().stream()
                .filter(m -> m.getEnabled() && m.getSeasonId() == 0 && m.getBoundArtifactVoucherCount() > 0)
                .findFirst().orElseThrow();
        ClaimTrialJournalMilestoneResponse mVoucherResp = rpc(f, p, SERVICE, "ClaimTrialJournalMilestone",
                ClaimTrialJournalMilestoneRequest.newBuilder()
                        .setClientRequestId(5003L).setMilestoneId(voucherMilestone.getId()).build(),
                ClaimTrialJournalMilestoneResponse.parser());
        assertThat(mVoucherResp.getErrorMessage().getId()).isZero();
        assertThat(mVoucherResp.getGrantedVoucherCount()).isEqualTo(voucherMilestone.getBoundArtifactVoucherCount());
        // 核对发出的拓印全部 bound=true
        List<BagItem> vouchers = p.bags().bag(BagType.INVENTORY).items().stream()
                .filter(it -> it.configId() == TrialJournalRules.BOUND_ARTIFACT_VOUCHER_ITEM_ID).toList();
        assertThat(vouchers).isNotEmpty().allMatch(BagItem::bound);
        assertThat(f.audit.items).anyMatch(a -> a.reason() == AssetAudit.Reason.TRIAL_JOURNAL_MILESTONE);

        // 3. 458 拓印兑换指定法宝 3301，产出 bound=true + 含空 EquipmentState，幂等重放返回同一 item_id
        long voucherBefore = p.bags().bag(BagType.INVENTORY).total(TrialJournalRules.BOUND_ARTIFACT_VOUCHER_ITEM_ID);
        RedeemBoundArtifactVoucherRequest redeemReq = RedeemBoundArtifactVoucherRequest.newBuilder()
                .setClientRequestId(6001L).setArtifactItemConfigId(3301).build();
        RedeemBoundArtifactVoucherResponse redeemResp = rpc(f, p, SERVICE, "RedeemBoundArtifactVoucher",
                redeemReq, RedeemBoundArtifactVoucherResponse.parser());
        assertThat(redeemResp.getErrorMessage().getId()).isZero();
        assertThat(redeemResp.getReplayed()).isFalse();
        assertThat(redeemResp.getCreatedItemId()).isPositive();
        assertThat(redeemResp.getArtifactItemConfigId()).isEqualTo(3301);
        BagItem redeemed = p.bags().bag(BagType.INVENTORY).item(redeemResp.getCreatedItemId());
        assertThat(redeemed).isNotNull();
        assertThat(redeemed.bound()).isTrue();
        assertThat(redeemed.equipment()).isNotNull();
        assertThat(p.bags().bag(BagType.INVENTORY).total(TrialJournalRules.BOUND_ARTIFACT_VOUCHER_ITEM_ID))
                .isEqualTo(voucherBefore - 1L);
        assertThat(f.audit.items).anyMatch(a -> a.reason() == AssetAudit.Reason.TRIAL_ARTIFACT_REDEEM);

        // 幂等重放不再扣拓印，返回原 item_id
        RedeemBoundArtifactVoucherResponse redeemReplay = rpc(f, p, SERVICE, "RedeemBoundArtifactVoucher",
                redeemReq, RedeemBoundArtifactVoucherResponse.parser());
        assertThat(redeemReplay.getErrorMessage().getId()).isZero();
        assertThat(redeemReplay.getReplayed()).isTrue();
        assertThat(redeemReplay.getCreatedItemId()).isEqualTo(redeemResp.getCreatedItemId());
        assertThat(p.bags().bag(BagType.INVENTORY).total(TrialJournalRules.BOUND_ARTIFACT_VOUCHER_ITEM_ID))
                .isEqualTo(voucherBefore - 1L);

        // 4. 非法法宝 ID 被拒（35008）
        RedeemBoundArtifactVoucherResponse badArt = rpc(f, p, SERVICE, "RedeemBoundArtifactVoucher",
                RedeemBoundArtifactVoucherRequest.newBuilder().setClientRequestId(6002L).setArtifactItemConfigId(3403).build(),
                RedeemBoundArtifactVoucherResponse.parser());
        assertThat(badArt.getErrorMessage().getId()).isEqualTo(TrialJournalService.ARTIFACT_INVALID);

        // 5. 满包兑换原子回滚（拓印张数与 revision 不变）
        f.bags.addItems(p, BagType.INVENTORY, Map.of(TrialJournalRules.BOUND_ARTIFACT_VOUCHER_ITEM_ID, 2L),
                true, AssetAudit.Reason.GM_GRANT, 0L, "");
        int freeSlots = p.bags().bag(BagType.INVENTORY).capacity() - p.bags().bag(BagType.INVENTORY).items().size();
        for (int i = 0; i < freeSlots; i++) {
            assertThat(f.bags.addItems(p, BagType.INVENTORY, Map.of(3302, 1L), true,
                    AssetAudit.Reason.GM_GRANT, 0L, "").ok()).isTrue();
        }
        long vouchersFullBefore = p.bags().bag(BagType.INVENTORY).total(TrialJournalRules.BOUND_ARTIFACT_VOUCHER_ITEM_ID);
        long revBefore = p.trialJournal().state().getStateRevision();
        RedeemBoundArtifactVoucherResponse fullResp = rpc(f, p, SERVICE, "RedeemBoundArtifactVoucher",
                RedeemBoundArtifactVoucherRequest.newBuilder().setClientRequestId(6003L).setArtifactItemConfigId(3301).build(),
                RedeemBoundArtifactVoucherResponse.parser());
        assertThat(fullResp.getErrorMessage().getId()).isEqualTo(TrialJournalService.BAG_FULL);
        assertThat(p.bags().bag(BagType.INVENTORY).total(TrialJournalRules.BOUND_ARTIFACT_VOUCHER_ITEM_ID))
                .isEqualTo(vouchersFullBefore);
        assertThat(p.trialJournal().state().getStateRevision()).isEqualTo(revBefore);
    }
}
