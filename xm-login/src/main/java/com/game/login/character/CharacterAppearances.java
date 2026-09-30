package com.game.login.character;

import java.util.Set;

/**
 * 建角可选的人物外观白名单（与 mmorpg {@code character_appearance.go} 一致）。键是稳定资源身份，不是职业或性别编号；
 * 空串合法且保持为空（旧客户端 / 机器人不选外观），不自动分配。删掉的人物不在白名单里，不做前缀猜测。
 */
public final class CharacterAppearances {

    private static final Set<String> ALLOWED = Set.of(
            "00_reference_topright_boy",
            "01_ice_sword_girl",
            "02_fire_talisman_boy",
            "03_lotus_healer_girl",
            "04_mountain_guardian_boy",
            "05_celestial_musician_girl",
            "06_thunder_caster_boy",
            "07_moon_shadow_assassin_girl",
            "08_alchemy_prodigy_boy",
            "09_bamboo_archer_girl",
            "10_crimson_spear_girl",
            "14_short_hair_snow_summoner_girl",
            "15_water_dragon_scholar_boy",
            "17_ghost_script_calligrapher_boy",
            "20_star_formation_master_girl");

    private CharacterAppearances() {
    }

    public static boolean isAllowed(String appearanceId) {
        return appearanceId.isEmpty() || ALLOWED.contains(appearanceId);
    }
}
