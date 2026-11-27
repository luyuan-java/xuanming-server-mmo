package com.game.scene.trial;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.engine.TrialJournalRules;
import com.game.player.store.state.PlayerTrialJournalState;
import com.game.player.store.state.SeasonTrialJournalArchiveState;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleTrialJournalFacts;
import com.game.proto.ClaimTrialJournalMilestoneRequest;
import com.game.proto.ClaimTrialJournalMilestoneResponse;
import com.game.proto.GetAppearanceInfoRequest;
import com.game.proto.GetAppearanceInfoResponse;
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

        // 1. 初始快照：4 卷全部可见，永久分母 36 枚（含 V2 双卫同阵 1051..1053 与 V3 卷四三境明雷 4011..4033），未通普通首领时困难未解锁
        GetTrialJournalResponse initResp = rpc(f, p, SERVICE, "GetTrialJournal",
                GetTrialJournalRequest.getDefaultInstance(), GetTrialJournalResponse.parser());
        assertThat(initResp.getErrorMessage().getId()).isZero();
        TrialJournalSnapshot initSnap = initResp.getSnapshot();
        assertThat(initSnap.getAvailable()).isTrue();
        assertThat(initSnap.getTotalPermanentSeals()).isEqualTo(36);
        assertThat(initSnap.getTotalPermanentLitSeals()).isZero();
        assertThat(initSnap.getHardBossTrialUnlocked()).isFalse();
        assertThat(initSnap.getChaptersList()).hasSize(4);
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
        assertThat(res.getTrialBattleId()).isEqualTo(90001L);

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
        assertThat(entry101.getVowSupported()).isTrue();
        assertThat(entry101.getSoloRecorded()).isTrue();
        assertThat(entry101.getSoloBest().getBestRounds()).isEqualTo(5);
        assertThat(entry101.getSoloBest().getBattleId()).isEqualTo(90001L);
        assertThat(entry101.getSoloBest().getLoadout().getArtifactEffectIdsList()).containsExactly(1, 5);
        assertThat(entry101.getCoopRecorded()).isTrue();
        assertThat(entry101.getCoopBest().getBestRounds()).isEqualTo(4);
        assertThat(entry101.getCoopBest().getBattleId()).isEqualTo(90002L);
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

    @Test
    void 里程碑M302授灵宠幻化月弦师_M105授技能特效寒星流华且支持幂等重放与专属校验() throws Exception {
        BattleFixture f = new BattleFixture();
        alignActiveSeason(f);
        ScenePlayer p = f.enter(11, 2003L);

        // 1. 快照正确带出 M105（寒星流华）与 M302（月弦师）专属外观展示字段，且表校验拦截可购买外观
        GetTrialJournalResponse initResp = rpc(f, p, SERVICE, "GetTrialJournal",
                GetTrialJournalRequest.getDefaultInstance(), GetTrialJournalResponse.parser());
        TrialJournalMilestoneView m105View = initResp.getSnapshot().getMilestonesList().stream()
                .filter(m -> m.getMilestoneId() == 105).findFirst().orElseThrow();
        assertThat(m105View.getGrantSkillVfxId()).isEqualTo(3);
        assertThat(m105View.getGrantSkillVfxName()).isEqualTo("寒星流华");

        TrialJournalMilestoneView m302View = initResp.getSnapshot().getMilestonesList().stream()
                .filter(m -> m.getMilestoneId() == 302).findFirst().orElseThrow();
        assertThat(m302View.getGrantPetCosmeticId()).isEqualTo(3);
        assertThat(m302View.getGrantPetCosmeticName()).isEqualTo("月弦师");

        assertThat(f.trialJournalTables.isValidExclusivePetCosmetic(1)).isFalse();
        assertThat(f.trialJournalTables.isValidExclusivePetCosmetic(3)).isTrue();
        assertThat(f.trialJournalTables.isValidExclusiveSkillVfx(1)).isFalse();
        assertThat(f.trialJournalTables.isValidExclusiveSkillVfx(3)).isTrue();

        // 2. 点亮卷一全部 15 枚战法印（bit 0..11 + 30..32）与卷三 S1 的 4 枚赛季印
        byte[] ch1All15 = new byte[5];
        ch1All15[0] = (byte) 0xFF; // 1011..1032 (bits 0..7)
        ch1All15[1] = (byte) 0x0F; // 1033..1043 (bits 8..11)
        ch1All15[3] = (byte) 0xC0; // 1051..1052 (bits 30..31)
        ch1All15[4] = (byte) 0x01; // 1053 (bit 32)
        p.trialJournal().replace(PlayerTrialJournalState.newBuilder()
                .setLitSealBits(ByteString.copyFrom(ch1All15))
                .addSeasonRecords(SeasonTrialJournalArchiveState.newBuilder()
                        .setSeasonId(f.season.activeSeasonId())
                        .addAllLitSealIds(List.of(3011, 3012, 3021, 3022)))
                .build());

        // 3. 领取 M302：永久解锁灵宠幻化 #3「月弦师」并支持幂等重放
        ClaimTrialJournalMilestoneRequest m302Req = ClaimTrialJournalMilestoneRequest.newBuilder()
                .setClientRequestId(7001L).setMilestoneId(302).build();
        ClaimTrialJournalMilestoneResponse m302Resp = rpc(f, p, SERVICE, "ClaimTrialJournalMilestone",
                m302Req, ClaimTrialJournalMilestoneResponse.parser());
        assertThat(m302Resp.getErrorMessage().getId()).isZero();
        assertThat(m302Resp.getGrantedSandCount()).isEqualTo(1);
        assertThat(m302Resp.getGrantedPetCosmeticId()).isEqualTo(3);

        ClaimTrialJournalMilestoneResponse m302Replay = rpc(f, p, SERVICE, "ClaimTrialJournalMilestone",
                m302Req, ClaimTrialJournalMilestoneResponse.parser());
        assertThat(m302Replay.getErrorMessage().getId()).isZero();
        assertThat(m302Replay.getReplayed()).isTrue();
        assertThat(m302Replay.getGrantedPetCosmeticId()).isEqualTo(3);

        // 4. 领取 M105：永久解锁技能特效 #3「寒星流华」并支持幂等重放（不自动穿戴）
        ClaimTrialJournalMilestoneRequest m105Req = ClaimTrialJournalMilestoneRequest.newBuilder()
                .setClientRequestId(7002L).setMilestoneId(105).build();
        ClaimTrialJournalMilestoneResponse m105Resp = rpc(f, p, SERVICE, "ClaimTrialJournalMilestone",
                m105Req, ClaimTrialJournalMilestoneResponse.parser());
        assertThat(m105Resp.getErrorMessage().getId()).isZero();
        assertThat(m105Resp.getGrantedSkillVfxId()).isEqualTo(3);

        ClaimTrialJournalMilestoneResponse m105Replay = rpc(f, p, SERVICE, "ClaimTrialJournalMilestone",
                m105Req, ClaimTrialJournalMilestoneResponse.parser());
        assertThat(m105Replay.getErrorMessage().getId()).isZero();
        assertThat(m105Replay.getReplayed()).isTrue();
        assertThat(m105Replay.getGrantedSkillVfxId()).isEqualTo(3);

        // 5. 衣柜快照核验 #3 月弦师与 #3 寒星流华均已永久拥有，且未自动替玩家穿戴
        GetAppearanceInfoResponse appResp = rpc(f, p, "SceneAppearanceClientPlayer", "GetAppearanceInfo",
                GetAppearanceInfoRequest.getDefaultInstance(), GetAppearanceInfoResponse.parser());
        assertThat(appResp.getErrorMessage().getId()).isZero();
        assertThat(appResp.getInfo().getPetCosmeticsList().stream()
                .filter(c -> c.getConfigId() == 3).findFirst().orElseThrow().getOwned()).isTrue();
        assertThat(appResp.getInfo().getSkillVfxCosmeticsList().stream()
                .filter(c -> c.getConfigId() == 3).findFirst().orElseThrow().getOwned()).isTrue();
        assertThat(appResp.getInfo().getEquippedSkillVfxMap()).isEmpty();
    }

    @Test
    void 卷四三境明雷战法印与里程碑_试炼誓约最高纪录及未知字段往返保留() throws Exception {
        BattleFixture f = new BattleFixture();
        alignActiveSeason(f);
        ScenePlayer p = f.enter(11, 2004L);

        // 1. 未知字段领域往返证明（对应 PlayerStateSchemaLedgerTest.ALLOW_ADDITIONS 的三张试炼格纪录消息）
        var entryUnknown = com.google.protobuf.UnknownFieldSet.newBuilder()
                .addField(999, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(12345L).build()).build();
        var soloUnknown = com.google.protobuf.UnknownFieldSet.newBuilder()
                .addField(998, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(54321L).build()).build();
        var coopUnknown = com.google.protobuf.UnknownFieldSet.newBuilder()
                .addField(997, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(67890L).build()).build();
        p.trialJournal().replace(PlayerTrialJournalState.newBuilder()
                .addRecords(com.game.player.store.state.TrialJournalEntryRecordState.newBuilder()
                        .setEntryId(401).setRuleVersion(1)
                        .setSoloBest(com.game.player.store.state.TrialJournalSoloRecordState.newBuilder()
                                .setBestRounds(12).setAchievedAtMs(1L).setBattleId(10L)
                                .setUnknownFields(soloUnknown))
                        .setCoopBest(com.game.player.store.state.TrialJournalCoopRecordState.newBuilder()
                                .setBestRounds(12).setPartySize(2).setAchievedAtMs(1L).setBattleId(11L)
                                .setUnknownFields(coopUnknown))
                        .setUnknownFields(entryUnknown))
                .build());

        // 2. 401 风眼引风（2 条誓约，7 回合）点亮 4011/4012/4013 + 刷新单人最佳与誓约最佳 + 保留未知字段
        int mask2 = TrialJournalRules.VOW_BAN_CONTROL | TrialJournalRules.VOW_TIGHT_ROUNDS;
        f.fighting(p.playerId(), 99101L);
        f.deliver(BattleFixture.settlement(p.playerId(), 99101L, 0L)
                .setBattleConfigId(113).setMatchMode(4).setTotalRounds(7)
                .setBossTrialKind(12).setBattleDifficulty(1).setFinishedAtMs(f.clock.epochMillis())
                .setTrialFacts(BattleTrialJournalFacts.newBuilder()
                        .setJournalEntryId(401).setTrialRuleVersion(1).setSeasonId(0)
                        .setInitialHumanCount(1).setBossWindHitUnprotectedDeath(false)
                        .setChallengeVowMask(mask2))
                .build());
        f.drain();
        f.completeSaves(ProgressResult.SAVED);

        var rec401 = p.trialJournal().toState().getRecordsList().stream()
                .filter(r -> r.getEntryId() == 401 && r.getRuleVersion() == 1).findFirst().orElseThrow();
        assertThat(rec401.getUnknownFields()).isEqualTo(entryUnknown);
        assertThat(rec401.getSoloBest().getUnknownFields()).isEqualTo(soloUnknown);
        assertThat(rec401.getCoopBest().getUnknownFields()).isEqualTo(coopUnknown);
        assertThat(rec401.getBestVowMask()).isEqualTo(mask2);
        assertThat(rec401.getBestVowCount()).isEqualTo(2);
        assertThat(rec401.getBestVowRounds()).isEqualTo(7);
        assertThat(rec401.getBestVowBattleId()).isEqualTo(99101L);

        // 校验战果回显字段 47..49
        GetRecentBattleResultsResponse r1 = rpc(f, p, "SceneBattleResultClientPlayer", "GetRecentBattleResults",
                GetRecentBattleResultsRequest.getDefaultInstance(), GetRecentBattleResultsResponse.parser());
        assertThat(r1.getResults(0).getTrialVowMask()).isEqualTo(mask2);
        assertThat(r1.getResults(0).getTrialNewVowBest()).isTrue();
        assertThat(r1.getResults(0).getTrialBattleId()).isEqualTo(99101L);

        // 3. 更少誓约条数（1 条，4 回合）刷单人最快但不覆盖誓约最高纪录
        f.fighting(p.playerId(), 99102L);
        f.deliver(BattleFixture.settlement(p.playerId(), 99102L, 0L)
                .setBattleConfigId(113).setMatchMode(4).setTotalRounds(4)
                .setBossTrialKind(12).setBattleDifficulty(1).setFinishedAtMs(f.clock.epochMillis())
                .setTrialFacts(BattleTrialJournalFacts.newBuilder()
                        .setJournalEntryId(401).setTrialRuleVersion(1).setSeasonId(0)
                        .setInitialHumanCount(1).setChallengeVowMask(TrialJournalRules.VOW_BAN_CONTROL))
                .build());
        f.drain();
        f.completeSaves(ProgressResult.SAVED);
        assertThat(p.trialJournal().state().getRecords(0).getBestVowCount()).isEqualTo(2);
        assertThat(p.trialJournal().state().getRecords(0).getBestVowBattleId()).isEqualTo(99101L);

        // 4. 同誓约条数更短回合（2 条，5 回合）刷新誓约纪录；更多条数（3 条，9 回合）再刷新
        f.fighting(p.playerId(), 99103L);
        f.deliver(BattleFixture.settlement(p.playerId(), 99103L, 0L)
                .setBattleConfigId(113).setMatchMode(4).setTotalRounds(5)
                .setBossTrialKind(12).setBattleDifficulty(1).setFinishedAtMs(f.clock.epochMillis())
                .setTrialFacts(BattleTrialJournalFacts.newBuilder()
                        .setJournalEntryId(401).setTrialRuleVersion(1).setSeasonId(0)
                        .setInitialHumanCount(1).setChallengeVowMask(mask2))
                .build());
        f.drain();
        f.completeSaves(ProgressResult.SAVED);
        assertThat(p.trialJournal().state().getRecords(0).getBestVowRounds()).isEqualTo(5);
        assertThat(p.trialJournal().state().getRecords(0).getBestVowBattleId()).isEqualTo(99103L);

        int mask3 = TrialJournalRules.VOW_BAN_CONTROL | TrialJournalRules.VOW_SINGLE_ARTIFACT | TrialJournalRules.VOW_NO_PET;
        f.fighting(p.playerId(), 99104L);
        f.deliver(BattleFixture.settlement(p.playerId(), 99104L, 0L)
                .setBattleConfigId(113).setMatchMode(4).setTotalRounds(9)
                .setBossTrialKind(12).setBattleDifficulty(1).setFinishedAtMs(f.clock.epochMillis())
                .setTrialFacts(BattleTrialJournalFacts.newBuilder()
                        .setJournalEntryId(401).setTrialRuleVersion(1).setSeasonId(0)
                        .setInitialHumanCount(1).setChallengeVowMask(mask3))
                .build());
        f.drain();
        f.completeSaves(ProgressResult.SAVED);

        // 5. 402 水镜钟鸣 + 403 涨退潮点满卷四 9 印 -> M401(4印) 与 M402(8印) 均可领取
        f.fighting(p.playerId(), 99105L);
        f.deliver(BattleFixture.settlement(p.playerId(), 99105L, 0L)
                .setBattleConfigId(114).setMatchMode(4).setTotalRounds(8)
                .setBossTrialKind(13).setBattleDifficulty(1).setFinishedAtMs(f.clock.epochMillis())
                .setTrialFacts(BattleTrialJournalFacts.newBuilder()
                        .setJournalEntryId(402).setTrialRuleVersion(1).setSeasonId(0)
                        .setInitialHumanCount(1)
                        .setDealtYangBellPhysicalDamage(true)
                        .setDealtYinDrumMagicDamage(true))
                .build());
        f.drain();
        f.completeSaves(ProgressResult.SAVED);

        f.fighting(p.playerId(), 99106L);
        f.deliver(BattleFixture.settlement(p.playerId(), 99106L, 0L)
                .setBattleConfigId(115).setMatchMode(4).setTotalRounds(8)
                .setBossTrialKind(14).setBattleDifficulty(1).setFinishedAtMs(f.clock.epochMillis())
                .setTrialFacts(BattleTrialJournalFacts.newBuilder()
                        .setJournalEntryId(403).setTrialRuleVersion(1).setSeasonId(0)
                        .setInitialHumanCount(1)
                        .setFinishedInEbbTide(true))
                .build());
        f.drain();
        f.completeSaves(ProgressResult.SAVED);

        GetTrialJournalResponse snapResp = rpc(f, p, SERVICE, "GetTrialJournal",
                GetTrialJournalRequest.getDefaultInstance(), GetTrialJournalResponse.parser());
        var ch4 = snapResp.getSnapshot().getChaptersList().stream()
                .filter(c -> c.getChapterId() == TrialJournalRules.CHAPTER_WILD_REALMS).findFirst().orElseThrow();
        assertThat(ch4.getLitSealCount()).isEqualTo(9);
        assertThat(ch4.getTotalSealCount()).isEqualTo(9);
        var e401 = ch4.getEntriesList().stream().filter(e -> e.getEntryId() == 401).findFirst().orElseThrow();
        assertThat(e401.getVowSupported()).isTrue();
        assertThat(e401.getBestVowMask()).isEqualTo(mask3);
        assertThat(e401.getBestVowCount()).isEqualTo(3);
        assertThat(e401.getBestVowRounds()).isEqualTo(9);
        assertThat(e401.getBestVowBattleId()).isEqualTo(99104L);

        ClaimTrialJournalMilestoneResponse m401 = rpc(f, p, SERVICE, "ClaimTrialJournalMilestone",
                ClaimTrialJournalMilestoneRequest.newBuilder().setClientRequestId(8401L).setMilestoneId(401).build(),
                ClaimTrialJournalMilestoneResponse.parser());
        assertThat(m401.getErrorMessage().getId()).isZero();
        ClaimTrialJournalMilestoneResponse m402 = rpc(f, p, SERVICE, "ClaimTrialJournalMilestone",
                ClaimTrialJournalMilestoneRequest.newBuilder().setClientRequestId(8402L).setMilestoneId(402).build(),
                ClaimTrialJournalMilestoneResponse.parser());
        assertThat(m402.getErrorMessage().getId()).isZero();
    }
}
