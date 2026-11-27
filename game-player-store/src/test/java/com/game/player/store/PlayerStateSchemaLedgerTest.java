package com.game.player.store;

import com.game.contract.check.persist.PersistentSchemaLedger;
import com.game.contract.check.persist.PersistentSchemaSnapshot;
import com.game.player.store.state.PlayerState;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 正常 Maven test/verify 的持久字段门禁；不写台账，不在缺少原件时自动重新建立基线。 */
class PlayerStateSchemaLedgerTest {
    // 第一至七代登记技能、宠物回执、宝宝条目与培养阶、角色形象试用/永久衣柜、宝宝技能重数和装备固定词条。第八代追加 ItemGainBlockState 与 ActivityVitalityState；第九代追加近期战斗结果、客户端请求幂等账本与神秘商人限购；第十代追加单槽赛季印与赛季追赶幂等回执；第十一代追加物品绑定、起步首宠、装备品相/保底/宗师署名、两阶段材料总账、近期战果试炼/材料回显、五行试炼志与手艺制书；第十二代追加瑞兽迎春活动进度/领奖/开局意图、近期战果瑞兽进度增量与账号级阶段一可交易配额确认水位；第十三代追加宝宝条目 3 套预设方案与灵阶突破幂等回执、人物战法构筑 3 套装配方案与乐观锁回执；第十四代追加官方灵石百宝阁个人与共享组限购记录；第十五代追加近期战果赛季印结算增量与首次解锁永久零数值纪念称谓标记；第十六代追加近期战果 PVP 延迟投降标记 surrendered；第十七代追加衣柜灵宠幻化/技能特效永久拥有位图与穿戴字段（AppearanceState 7..9、PetEntry 26）；第十八代追加角色累计经验 PlayerProgressionState、全玩法周银两账本 BattleEconomyLedgerState 与近期战果成长字段（BattleResultEntryState 40..46）；第十九代追加激励视频广告日账本与会话持久态 RewardedAdLedgerState（ClientRequestLedgerState 6）及领奖幂等回执字段（ClientRequestRecordState 30..31）；第二十代追加青云游历手记持久态 PlayerTravelPassState（PlayerState 24）与领奖幂等件数回执（ClientRequestRecordState 32）。
    // ... 第二十一代追加 7 套非永久「战法·加点」流派方案自定义名称、按套租用状态（PlayerSkillBookState 8..9、SkillLoadoutSchemeState 6）及租用幂等回执字段（ClientRequestRecordState 33..35）；第二十二代追加赛季 V4 永久徽章动态位图、往季追忆目标与进度、按期重置标记及近期战果往季追忆回显（PlayerSeasonState 2..4、PlayerSeasonSealRecordState 7、BattleResultEntryState 47..51）；第二十三代追加离线/跨线名匠代打口碑、雅号与未读营业战报（PlayerCraftState 10..20）；第二十四代追加防“上班化”蓄水池滚存字段（EquipmentMaterialRewardLedgerState 12、BattleEconomyLedgerState 4..5）；第二十五代追加五行试炼志 V3 权威 battle_id、个人最高誓约纪录与近期战果誓约/回放回显（TrialJournalSoloRecordState 4、TrialJournalCoopRecordState 6、TrialJournalEntryRecordState 5..8、BattleResultEntryState 52..54）；第二十六代追加实时技能冷却与下线保留 Buff 存档字段（PlayerState 26..27：SkillCooldownListState、SavedBuffListState）。
    // ... 第二十六代追加实时技能冷却与下线保留 Buff 存档字段（PlayerState 26..27：SkillCooldownListState、SavedBuffListState）；第二十七代追加个人局外人观战交流偏好（PlayerState 28：spectate_chat_pref）。
    private static final String GENESIS_SHA256 = "3cd2f18d61634eaabe7d5805df027877451816242bb41ff5aecb704177d7877d";
    private static final String HEAD_SHA256 = "6664fc5e7edb618bb00e7ef6f91d40d52c36e342b174d7afb7bab13fecd75866";

    // 仅登记已经存在的领域 restore→修改→toState 未知字段往返证据；protobuf 自己的 parse/serialize 不能替代它。
    private static final Set<String> ALLOW_ADDITIONS = Set.of(
            "game.storage.PlayerState", // ScenePlayer 持有根未知字段，技能修习后存盘的领域回归保护。
            "game.storage.AttributeState", // AttributeFeatureTest：加载规整后未知字段仍在。
            "game.storage.PetState", // PetServiceTest：加载、改名、候选提交和存盘保留未知字段与回执。
            "game.storage.PetEntry", // PlayerPetsUnknownFieldsTest：宝宝实例恢复、改名、存盘保留未知字段与条目回执。
            "game.storage.BattleLedgerState", "game.storage.BattleLedgerEntry", // BattleLedgerTest / BattleLedgerEntryFieldsTest。
            "game.storage.AssetOpLedgerState", "game.storage.AssetOpStreamLedgerState",
            "game.storage.AssetOpRejectionState", // AssetOpLedgerTest：各层记账前后保留。
            "game.storage.AppearanceState", // PlayerAppearanceTest：试用位图、未知高位与未知字段往返保留。
            "game.storage.EquipmentState", // EquipmentServiceTest：拆宝石、跨玩家搬运再存盘保留固定词条及嵌套未知字段。
            "game.storage.ClientRequestLedgerState", // BattleProgressionParityTest：PlayerClientRequestLedger 恢复与存盘保留未知字段往返。
            "game.storage.ClientRequestRecordState", // PlayerClientRequestLedger：toBuilder 原样保留已有记录及未知字段。
            "game.storage.BagItemState", // CoreGameplayLoopParityTest：BagItem 原样保留未知字段并跨绑定分堆/搬运往返。
            "game.storage.EquipmentFixedAffixState", // CoreGameplayLoopParityTest：EquipmentState 嵌套固定词条保留未知字段往返。
            "game.storage.EquipmentMaterialRewardLedgerState", // CoreGameplayLoopParityTest：toBuilder 保留未知字段往返。
            "game.storage.BattleEconomyLedgerState", // BattleProgressionParityTest：BattleEconomyLedgerState 恢复与存盘保留未知字段往返。
            "game.storage.BattleResultEntryState", // CoreGameplayLoopParityTest：PlayerBattleResults 确认已读保留未知字段往返。
            "game.storage.PlayerSkillBookState", // ClassSkillClientTest：PlayerSkills 恢复、装配方案保存与存盘保留未知字段往返。
            "game.storage.SkillLoadoutSchemeState", // SkillLoadoutTest：单套方案未知字段存档往返原样保留。
            "game.storage.PlayerSeasonState", // SeasonFeatureAndServiceTest：PlayerSeason 恢复、解锁徽章与存盘保留未知字段往返。
            "game.storage.PlayerSeasonSealRecordState", // SeasonFeatureAndServiceTest：赛季印记录 toBuilder 原样保留未知字段往返。
            "game.storage.PlayerCraftState", // EquipQualityAndSkillCraftTest：PlayerCraft 恢复、确认战报与存盘保留未知字段往返。
            "game.storage.TrialJournalSoloRecordState", // TrialJournalFeatureAndServiceTest：单人最佳纪录恢复、更新与存盘保留未知字段往返。
            "game.storage.TrialJournalCoopRecordState", // TrialJournalFeatureAndServiceTest：合战最佳纪录恢复、更新与存盘保留未知字段往返。
            "game.storage.TrialJournalEntryRecordState", // TrialJournalFeatureAndServiceTest：试炼格纪录恢复、更新与存盘保留未知字段往返。
            "game.storage.SkillCooldownListState", // RealtimeCooldownBuffPersistenceTest：空/非空冷却列表未知字段往返。
            "game.storage.SavedBuffListState", // RealtimeCooldownBuffPersistenceTest：下线保留 Buff 列表未知字段往返。
            "game.storage.SavedBuffEntryState"); // RealtimeCooldownBuffPersistenceTest：保留 Buff 条目与未识别表行未知字段往返。
    // 当前根闭包没有 enum；未来 enum 新值须另有原始数值往返证据，不能沿用 unknown fields 证明。

    @Test void playerStateDescriptorMatchesRegisteredPersistentHistory() throws Exception {
        var current = PersistentSchemaSnapshot.capture(PlayerState.getDescriptor());
        int generation = verifyRegisteredSchema(Path.of("src/test/resources/persistent-schema"),
                GENESIS_SHA256, HEAD_SHA256, current, ALLOW_ADDITIONS);
        assertThat(generation).isEqualTo(27);
    }

    /** 已合法退役为不可达的类型仍须保留历史解冻政策，不能只用当前闭包判定策略名有效。 */
    static int verifyRegisteredSchema(Path directory, String genesisSha256, String headSha256,
                                      PersistentSchemaSnapshot current, Set<String> allowAdditions) throws Exception {
        var history = PersistentSchemaLedger.read(directory, genesisSha256, headSha256);
        var knownTypes = new TreeSet<>(current.messages().keySet());
        knownTypes.addAll(current.enums().keySet());
        for (var entry : history) {
            knownTypes.addAll(entry.snapshot().messages().keySet());
            knownTypes.addAll(entry.snapshot().enums().keySet());
        }
        assertThat(knownTypes).as("允许新增策略必须对应历史或当前闭包中的 message/enum 类型").containsAll(allowAdditions);
        return PersistentSchemaLedger.verify(directory, genesisSha256, headSha256, current, allowAdditions);
    }
}
