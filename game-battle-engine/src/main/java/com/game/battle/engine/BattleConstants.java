package com.game.battle.engine;

/**
 * 回合制战斗引擎常量，逐项镜像基线 {@code cpp/libs/services/battle/constants/turn_battle_constants.h}（规格 §0.4）。
 *
 * <p>类型口径：C++ 的 {@code uint64_t} 常量用 {@code long}，{@code uint32_t} 用 {@code int}，都按无符号解释；
 * {@code double} 常量照原式书写（例如 {@link #FLEE_SPEED_FACTOR} 写成 {@code 0.01 / 12.0}，编译期求值与 C++ 逐位相同）。
 * 位枚举与 buff 类型的权威定义在实时战斗那边（基线 {@code services/scene}），这里只镜像数值，改动要两边同步。
 */
public final class BattleConstants {

    private BattleConstants() {
    }

    // ---- 时间 → 回合换算 ----

    /** 一回合等效的挂钟毫秒数：rounds = max(1, ceil(ms / 6000))（{@code constants.h:15}）。 */
    public static final long ROUND_DURATION_MS = 6000;
    /** {@code DungeonTable.time_limit} 为 0（或查不到副本行）时的回合上限（{@code constants.h:18}）。 */
    public static final int DEFAULT_MAX_ROUNDS = 30;

    // ---- 匹配模式（镜像 MatchMode；只有这两个值算 PVE，其余一律非 PVE） ----

    /** PVE 单人（{@code constants.h:22}）。 */
    public static final int MATCH_MODE_PVE_SOLO = 4;
    /** PVE 组队（{@code constants.h:23}）。 */
    public static final int MATCH_MODE_PVE_TEAM = 5;

    // ---- 队伍上限与自动战斗 ----

    /** 每队玩家数上限；只统计 {@code team_index <= 1} 的快照，宝宝不计（{@code constants.h:30}）。 */
    public static final int MAX_BATTLE_TEAM_SIZE = 5;
    /** 全自动房间的回合推进间隔（只有 battle 节点用，{@code constants.h:35}）。 */
    public static final long AUTO_ROUND_INTERVAL_MS = 2000;

    // ---- 技能类型位号（SkillTable.skill_type 存的是位号，不是掩码；SkillPermission.skill_type 按位号平铺） ----

    public static final int SKILL_TYPE_BIT_PASSIVE = 0;
    public static final int SKILL_TYPE_BIT_GENERAL = 1;
    public static final int SKILL_TYPE_BIT_CHANNEL = 2;
    public static final int SKILL_TYPE_BIT_TOGGLE = 3;
    public static final int SKILL_TYPE_BIT_ACTIVATE = 4;
    public static final int SKILL_TYPE_BIT_BASIC_ATTACK = 5;

    // ---- 目标模式掩码（SkillTable.targeting_mode 存位号，比较的是 1 << 位号） ----

    public static final int TARGETING_NO_TARGET_REQUIRED = 1 << 0;
    public static final int TARGETING_TARGETED_SKILL = 1 << 1;
    public static final int TARGETING_AREA_OF_EFFECT = 1 << 2;

    // ---- buff 类型（BuffTable.buff_type） ----

    public static final int BUFF_TYPE_STUN = 30;
    public static final int BUFF_TYPE_SILENCE = 31;
    /** 引擎从不引用（基线无效果，规格 §11.1 第 5 条）。 */
    public static final int BUFF_TYPE_INVINCIBILITY = 32;
    /** 引擎从不引用（基线无效果，规格 §11.1 第 5 条）。 */
    public static final int BUFF_TYPE_IMMUNITY = 34;
    public static final int BUFF_TYPE_DISPEL = 35;
    public static final int BUFF_TYPE_HEALTH_REGENERATION = 40;
    /** 引擎从不引用（基线无效果，规格 §11.1 第 5 条）。 */
    public static final int BUFF_TYPE_MANA_REGENERATION = 41;
    public static final int BUFF_TYPE_HEALTH_REGENERATION_BASED_ON_LOST_HEALTH = 42;
    public static final int BUFF_TYPE_POISON = 50;
    public static final int BUFF_TYPE_BURN = 51;
    public static final int BUFF_TYPE_FREEZE = 52;

    // ---- 战斗状态（SkillPermission 的行 id） ----

    /** 沉默（{@code constants.h:70}）。 */
    public static final int COMBAT_STATE_SILENCE = 1;

    // ---- 回合规则 ----

    /** 普攻的基础伤害（普攻没有 SkillTable 行，{@code constants.h:76}）。 */
    public static final double BASIC_ATTACK_BASE_DAMAGE = 10.0;
    /** 非 PVE 对局里直接伤害（普攻 / 技能）再乘的系数；周期伤害不乘（{@code constants.h:84}）。 */
    public static final double PVP_DAMAGE_SCALE = 0.3;
    /** 逃跑基础成功率（{@code constants.h:89}）。 */
    public static final double FLEE_BASE_CHANCE = 0.5;
    /** 逃跑速度差系数；照 C++ 原式书写，值为 {@code 8.333333333333334E-4}（{@code constants.h:90}）。 */
    public static final double FLEE_SPEED_FACTOR = 0.01 / 12.0;
    public static final double FLEE_MIN_CHANCE = 0.05;
    public static final double FLEE_MAX_CHANCE = 0.95;
    /** 掉落概率分母：drop_rate 是万分比整数（{@code constants.h:99}）。 */
    public static final int DROP_RATE_DENOMINATOR = 10000;
    /** PVP 每人每场道具使用上限；PVE 不限（{@code constants.h:104}）。 */
    public static final int MAX_ITEM_USES_PER_BATTLE_PVP = 5;
    /** 子 buff 递归深度上限：判定是 {@code depth > 8}，深度 0..8 共 9 层都生效（{@code constants.h:107}）。 */
    public static final int MAX_SUB_BUFF_DEPTH = 8;
    /** 基础命中率（百分比）；≥ 100 时命中判定短路、不耗随机数（{@code constants.h:116}）。 */
    public static final int BASE_HIT_RATE = 100;
    /** SkillTable.cost_resource 里表示法力的资源 id；其余资源 id 忽略（{@code constants.h:121}）。 */
    public static final int SKILL_COST_RESOURCE_MANA = 1;
    /** 每队前排人数（基线只出现在注释里，{@code constants.h:125}）。 */
    public static final int FORMATION_FRONT_ROW_SIZE = 5;

    // ---- 局内 actor_id 命名空间（{@code constants.h:130-142}） ----

    /**
     * 引擎局内号的标志位（bit63）。玩家直接用 player_id（必须 &lt; 2^63，开局时强制）；怪物与宝宝用带此标志的局内序号，
     * 所以它们的 actor_id 在 {@code long} 里是负数，比较 / 排序 / 取模一律按无符号。
     */
    public static final long ENGINE_LOCAL_ACTOR_ID_FLAG = 1L << 63;
    /** 怪物局内 actor_id 起始值 {@code 0x8000000100000000}。 */
    public static final long MONSTER_ACTOR_ID_BASE = ENGINE_LOCAL_ACTOR_ID_FLAG | (1L << 32);
    /** 宝宝局内 actor_id 起始值 {@code 0x8000000200000000}。 */
    public static final long PET_ACTOR_ID_BASE = ENGINE_LOCAL_ACTOR_ID_FLAG | (2L << 32);

    // ---- 怪物默认属性（查不到 MonsterTable 行，或行的 health 为 0 时用；{@code constants.h:143-148}） ----

    public static final long MONSTER_DEFAULT_HEALTH = 300;
    public static final long MONSTER_DEFAULT_STRENGTH = 5;
    public static final long MONSTER_DEFAULT_ARMOR = 24;
    public static final long MONSTER_DEFAULT_RESISTANCE = 0;
    public static final long MONSTER_DEFAULT_CRIT_CHANCE = 0;
    public static final long MONSTER_DEFAULT_SPEED = 60;
}
