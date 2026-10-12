package com.game.battle.engine;

import com.game.table.BuffTable;
import com.game.table.CommonErrorTip;
import com.game.table.ItemTable;
import com.game.table.MonsterTable;
import com.game.table.Monsterdrop;
import com.game.table.SkillErrorTip;
import com.game.table.SkillPermissionTable;
import com.game.table.SkillTable;

/**
 * 引擎单测共用的标准表与 id（逐项移植基线 {@code turn_battle_engine_test.cpp:44-139} 的常量与 {@code MakeProvider}，
 * 以及 {@code :1856-1880} 的 {@code MakeDropProvider}；规格 §13.4 开头的「标准表」）。所有引擎用例都从这里取表，
 * 需要「改一列」时对返回的 {@link MemoryBattleData} 再调 {@code addXxx(id)} 拿到同一个 builder 修改。
 *
 * <p>标准表一览（与基线逐项相同）：
 * <ul>
 *   <li>技能 101：指向性(位号 1)，普通施放，冷却组 9 = 12000 ms（2 回合），伤害定值 50；</li>
 *   <li>技能 102：指向性，普通施放，effect = 201（挂毒），无伤害；技能 103：指向性，普通施放，伤害 1000；
 *       技能 104：指向性，普通施放，effect = 220（驱散）；</li>
 *   <li>道具 301：battle_usable 1，回血 100；</li>
 *   <li>buff 201：毒(50)，12 s，间隔 6 s，interval_effect [10]，max_layer 3，tag {poison_tag}；buff 210：沉默(31)，12 s；
 *       buff 220：驱散(35)，dispel_tag {poison_tag}；buff 230：无限，immune_tag {poison_tag}；
 *       buff 240：按损血回血(42)，12 s，间隔 6 s，回血定值 25；</li>
 *   <li>SkillPermission 第 1 行（沉默）：[1000, 7005, 7005, 1000, 1000, 1000]；</li>
 *   <li>副本 7（{@link #DUNGEON_CONFIG}）在标准表里<strong>没有行</strong>：回合上限缺省 30，怪物是兜底怪（300 hp、str 5、armor 24、speed 60）。</li>
 * </ul>
 */
final class TestTables {

    // ---- 参战者（基线 kPlayerA/B/C、kMonsterId、kPetA） ----

    static final long PLAYER_A = 5001;
    static final long PLAYER_B = 5002;
    static final long PLAYER_C = 5003;
    /** 第 0 只怪的 actor_id（{@code kMonsterActorIdBase}）。 */
    static final long MONSTER_ID = BattleConstants.MONSTER_ACTOR_ID_BASE;
    /** 宝宝的真实 pet_id（不是它在战斗里的 actor_id）。 */
    static final long PET_A = 700001;

    // ---- 标准表 id（基线 :50-61） ----

    /** 普通伤害技能（冷却组 9）。 */
    static final int SKILL_DAMAGE = 101;
    /** 纯 buff 技能：挂毒。 */
    static final int SKILL_POISON = 102;
    /** 一击必杀。 */
    static final int SKILL_NUKE = 103;
    /** 纯驱散技能。 */
    static final int SKILL_DISPEL = 104;
    /** 毒：2 回合，每回合每层 10 点。 */
    static final int BUFF_POISON = 201;
    /** 沉默。 */
    static final int BUFF_SILENCE = 210;
    /** 纯驱散 buff。 */
    static final int BUFF_DISPEL = 220;
    /** 免疫毒 tag。 */
    static final int BUFF_IMMUNE_POISON = 230;
    /** 周期回血。 */
    static final int BUFF_REGEN = 240;
    /** 战斗药品（回血 100）。 */
    static final int ITEM_POTION = 301;
    static final int COOLDOWN_GROUP = 9;
    /** 标准请求的 battle_config_id；标准表里没有这一行。 */
    static final int DUNGEON_CONFIG = 7;

    // ---- 掉落 / 道具 / 快照清洗用例的 id（基线 :1856-1862） ----

    /** 必掉 {@link #ITEM_POTION} ×2 的测试怪（只在 {@link #withDrops()} 里有）。 */
    static final int MONSTER_WITH_DROP = 61;
    /** 只有 {@link #MONSTER_WITH_DROP} 的副本（只在 {@link #withDrops()} 里有）。 */
    static final int DUNGEON_WITH_DROP = 62;
    /** 纯回蓝药（由用例自己加行）。 */
    static final int ITEM_MANA_POTION = 302;
    /** 不能在战斗中使用的道具（由用例自己加行）。 */
    static final int ITEM_NOT_BATTLE_USABLE = 303;
    /** 被动技能：不该能提交（由用例自己加行）。 */
    static final int SKILL_PASSIVE_ONLY = 401;
    /** 控制类 buff：不该被快照带进来（由用例自己加行）。 */
    static final int BUFF_STUN_TABLE = 250;
    /** 瞬时类 buff：同上（由用例自己加行）。 */
    static final int BUFF_INSTANT_TABLE = 251;

    // ---- tip 码 ----

    static final int SUCCESS = CommonErrorTip.common_error.kSuccess_VALUE;
    static final int SILENCE_RESTRICTION = SkillErrorTip.skill_error.kSkillCannotBeCastSilenceRestriction_VALUE;

    private TestTables() {
    }

    /** 一套标准测试表（基线 {@code MakeProvider()}），每次调用返回新实例。 */
    static MemoryBattleData standard() {
        MemoryBattleData data = new MemoryBattleData();

        // 伤害技能：单体目标（targeting_mode 存位号，1 = 指向性），普通施放类型
        SkillTable.Builder damageSkill = data.addSkill(SKILL_DAMAGE);
        damageSkill.addTargetingMode(1);
        damageSkill.addSkillType(BattleConstants.SKILL_TYPE_BIT_GENERAL);
        damageSkill.setCooldownId(COOLDOWN_GROUP);
        data.setCooldownMs(COOLDOWN_GROUP, 12000); // 2 回合冷却
        data.setSkillDamage(SKILL_DAMAGE, 50.0);

        // 挂毒技能：无伤害纯 buff
        SkillTable.Builder poisonSkill = data.addSkill(SKILL_POISON);
        poisonSkill.addTargetingMode(1);
        poisonSkill.addSkillType(BattleConstants.SKILL_TYPE_BIT_GENERAL);
        poisonSkill.addEffect(BUFF_POISON);

        // 一击必杀
        SkillTable.Builder nukeSkill = data.addSkill(SKILL_NUKE);
        nukeSkill.addTargetingMode(1);
        nukeSkill.addSkillType(BattleConstants.SKILL_TYPE_BIT_GENERAL);
        data.setSkillDamage(SKILL_NUKE, 1000.0);

        // 纯驱散技能
        SkillTable.Builder dispelSkill = data.addSkill(SKILL_DISPEL);
        dispelSkill.addTargetingMode(1);
        dispelSkill.addSkillType(BattleConstants.SKILL_TYPE_BIT_GENERAL);
        dispelSkill.addEffect(BUFF_DISPEL);

        // 战斗药水：回血 100
        ItemTable.Builder potion = data.addItem(ITEM_POTION);
        potion.setBattleUsable(1);
        potion.setBattleHealHp(100);

        // 毒 buff：12 秒 → 2 回合，6 秒周期 → 每回合 tick，每层 10 点
        BuffTable.Builder poisonBuff = data.addBuff(BUFF_POISON);
        poisonBuff.setBuffType(BattleConstants.BUFF_TYPE_POISON);
        poisonBuff.setDuration(12.0);
        poisonBuff.setInterval(6.0);
        poisonBuff.addIntervalEffect(10.0);
        poisonBuff.setMaxLayer(3);
        poisonBuff.putTag("poison_tag", true);

        // 沉默 buff
        BuffTable.Builder silenceBuff = data.addBuff(BUFF_SILENCE);
        silenceBuff.setBuffType(BattleConstants.BUFF_TYPE_SILENCE);
        silenceBuff.setDuration(12.0);

        // 纯驱散 buff：驱掉毒 tag
        BuffTable.Builder dispelBuff = data.addBuff(BUFF_DISPEL);
        dispelBuff.setBuffType(BattleConstants.BUFF_TYPE_DISPEL);
        dispelBuff.putDispelTag("poison_tag", true);

        // 免疫毒 tag 的 buff
        BuffTable.Builder immuneBuff = data.addBuff(BUFF_IMMUNE_POISON);
        immuneBuff.setInfiniteDuration(1);
        immuneBuff.putImmuneTag("poison_tag", true);

        // 周期回血 buff：12 秒 → 2 回合，每回合回 25
        BuffTable.Builder regenBuff = data.addBuff(BUFF_REGEN);
        regenBuff.setBuffType(BattleConstants.BUFF_TYPE_HEALTH_REGENERATION_BASED_ON_LOST_HEALTH);
        regenBuff.setDuration(12.0);
        regenBuff.setInterval(6.0);
        data.setBuffRegen(BUFF_REGEN, 25.0);

        // 沉默许可行：格值按技能类型位号平铺，kSuccess = 放行，其余为错误码
        SkillPermissionTable.Builder permission = data.addSkillPermission(BattleConstants.COMBAT_STATE_SILENCE);
        permission.addSkillType(SUCCESS);             // 被动
        permission.addSkillType(SILENCE_RESTRICTION); // 普通施放：沉默禁用
        permission.addSkillType(SILENCE_RESTRICTION); // 吟唱
        permission.addSkillType(SUCCESS);             // 开关
        permission.addSkillType(SUCCESS);             // 激活
        permission.addSkillType(SUCCESS);             // 普攻

        return data;
    }

    /**
     * 标准表 + 一只「必掉药」的怪 + 只有它的副本（基线 {@code MakeDropProvider()}）：怪 61 血 1、速度 1、exp 7、gold 3，
     * 掉落 {301 ×2 @ 10000}；副本 62 的怪物组为 [61]、time_limit 1800 s。
     */
    static MemoryBattleData withDrops() {
        MemoryBattleData data = standard();
        MonsterTable.Builder monster = data.addMonster(MONSTER_WITH_DROP);
        monster.setHealth(1);      // 一刀秒，保证第一回合就打完
        monster.setSpeed(1);
        monster.setExpReward(7);
        monster.setGoldReward(3);
        monster.addDrop(Monsterdrop.newBuilder()
                .setDropItem(ITEM_POTION)
                .setDropCount(2)
                .setDropRate(10000)); // 万分比：必掉
        data.setDungeonMonsters(DUNGEON_WITH_DROP, MONSTER_WITH_DROP);
        data.addDungeon(DUNGEON_WITH_DROP).setTimeLimit(1800);
        return data;
    }
}
