package com.game.login.character;

import java.util.Set;

/**
 * 建角可选的人物外观白名单（与 mmorpg {@code character_appearance.go} 一致）。键是稳定资源身份，不是职业或性别编号；
 * 仅放行 2 名免费初始人物；空串合法且保持为空（旧客户端 / 机器人不选外观），其余现役人物由游戏内永久收藏解锁。
 */
public final class CharacterAppearances {

    private static final Set<String> ALLOWED = Set.of(
            "00_reference_topright_boy",
            "01_ice_sword_girl");

    private static final Set<String> RETIRED = Set.of(
            "02_fire_talisman_boy",
            "03_lotus_healer_girl",
            "05_celestial_musician_girl",
            "09_bamboo_archer_girl",
            "10_crimson_spear_girl",
            "15_water_dragon_scholar_boy",
            "07_moon_shadow_assassin_girl",
            "14_short_hair_snow_summoner_girl",
            "20_star_formation_master_girl");

    private static final Set<String> REMOVED = Set.of(
            "04_mountain_guardian_boy",
            "06_thunder_caster_boy",
            "08_alchemy_prodigy_boy",
            "17_ghost_script_calligrapher_boy");

    private CharacterAppearances() {
    }

    public static boolean isAllowed(String appearanceId) {
        return appearanceId.isEmpty() || ALLOWED.contains(appearanceId);
    }

    /** 退役或非初始建角身份只用于确认已提交建角的丢应答重试。 */
    public static boolean isRetired(String appearanceId) {
        return RETIRED.contains(appearanceId);
    }

    /** 已彻底删除的人物外观，不可在选角列表展示或进游戏。 */
    public static boolean isRemoved(String appearanceId) {
        return appearanceId != null && REMOVED.contains(appearanceId);
    }
}
