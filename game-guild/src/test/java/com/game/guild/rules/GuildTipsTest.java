package com.game.guild.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.guild.rules.RejectReply.MappingRepair;
import com.game.proto.TipInfoMessage;
import com.game.table.CommonErrorTip;
import com.game.table.GuildErrorTip;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * tip 码护栏（基线 constants_test.go）、每个发生点的英文原因串（guild-spec §0.4），以及仓储拒绝 → 答复的整表
 * （基线 TestMapWriteErrMapping，guild_manage_logic_test.go:384-460；§2.7）。
 */
class GuildTipsTest {

    /** 段声明之外的 int 常量（不是 tip 码）。 */
    private static final Set<String> SEGMENT_CONSTANTS = Set.of("SEGMENT_BASE", "SEGMENT_WIDTH");
    /** 取自通用段的码。 */
    private static final Set<String> COMMON_CODES = Set.of("SERVICE_UNAVAILABLE", "FEATURE_UNAVAILABLE", "MESSAGE_ID_NOT_FOUND");

    private static Map<String, Integer> guildCodes() {
        Map<String, Integer> codes = new LinkedHashMap<>();
        codes.put("ALREADY_IN_GUILD", GuildTips.ALREADY_IN_GUILD);
        codes.put("GUILD_NOT_FOUND", GuildTips.GUILD_NOT_FOUND);
        codes.put("NOT_IN_GUILD", GuildTips.NOT_IN_GUILD);
        codes.put("GUILD_FULL", GuildTips.GUILD_FULL);
        codes.put("LEADER_CANT_LEAVE", GuildTips.LEADER_CANT_LEAVE);
        codes.put("NOT_LEADER", GuildTips.NOT_LEADER);
        codes.put("NO_PERMISSION", GuildTips.NO_PERMISSION);
        codes.put("NOT_RANKED", GuildTips.NOT_RANKED);
        codes.put("ID_GEN_UNAVAILABLE", GuildTips.ID_GEN_UNAVAILABLE);
        codes.put("NAME_INVALID", GuildTips.NAME_INVALID);
        codes.put("NAME_TAKEN", GuildTips.NAME_TAKEN);
        codes.put("ANNOUNCEMENT_TOO_LONG", GuildTips.ANNOUNCEMENT_TOO_LONG);
        codes.put("HOME_ZONE_UNKNOWN", GuildTips.HOME_ZONE_UNKNOWN);
        codes.put("ZONE_MERGING", GuildTips.ZONE_MERGING);
        codes.put("TARGET_NOT_MEMBER", GuildTips.TARGET_NOT_MEMBER);
        codes.put("CANNOT_TARGET_SELF", GuildTips.CANNOT_TARGET_SELF);
        codes.put("RANK_TOO_LOW", GuildTips.RANK_TOO_LOW);
        codes.put("OFFICER_LIMIT", GuildTips.OFFICER_LIMIT);
        codes.put("APPLICATION_NOT_FOUND", GuildTips.APPLICATION_NOT_FOUND);
        codes.put("APPLICATION_LIMIT", GuildTips.APPLICATION_LIMIT);
        codes.put("APPLICATION_QUEUE_FULL", GuildTips.APPLICATION_QUEUE_FULL);
        codes.put("BUSY_RETRY", GuildTips.BUSY_RETRY);
        codes.put("FUNDS_INSUFFICIENT", GuildTips.FUNDS_INSUFFICIENT);
        codes.put("MAX_LEVEL", GuildTips.MAX_LEVEL);
        codes.put("DONATE_LIMIT", GuildTips.DONATE_LIMIT);
        codes.put("CURRENCY_INSUFFICIENT", GuildTips.CURRENCY_INSUFFICIENT);
        codes.put("ASSET_PENDING", GuildTips.ASSET_PENDING);
        codes.put("ASSET_REJECTED", GuildTips.ASSET_REJECTED);
        codes.put("SHOP_GOODS_NOT_FOUND", GuildTips.SHOP_GOODS_NOT_FOUND);
        codes.put("SHOP_LEVEL_TOO_LOW", GuildTips.SHOP_LEVEL_TOO_LOW);
        codes.put("SHOP_LIMIT", GuildTips.SHOP_LIMIT);
        codes.put("CONTRIBUTION_INSUFFICIENT", GuildTips.CONTRIBUTION_INSUFFICIENT);
        return codes;
    }

    // ================================================================ 码护栏

    @Test
    void 码都在guild段内且被生成枚举识别_覆盖生成枚举的全部值() {
        Set<Integer> covered = new HashSet<>();
        guildCodes().forEach((name, code) -> {
            assertThat(code).as("%s 落在 guild 段 [14000,15000) 之外", name).isBetween(14000, 14999);
            assertThat(GuildTips.isGuildCode(code)).as(name).isTrue();
            assertThat(GuildErrorTip.guild_error.forNumber(code)).as("%s = %d 未被生成枚举识别", name, code).isNotNull();
            covered.add(code);
        });
        // 反过来：生成枚举里的每个非 0 值都有常量（契约追加新码时这里提醒补上）
        for (GuildErrorTip.guild_error e : GuildErrorTip.guild_error.values()) {
            if (e != GuildErrorTip.guild_error.UNRECOGNIZED && e.getNumber() != 0) {
                assertThat(covered).as("生成枚举 %s 没有对应的 GuildTips 常量", e).contains(e.getNumber());
            }
        }
        assertThat(GuildTips.OK).isZero();
        assertThat(GuildTips.isGuildCode(GuildTips.OK)).isFalse();
        assertThat(GuildTips.isGuildCode(13999)).isFalse();
        assertThat(GuildTips.isGuildCode(14000)).isTrue();
        assertThat(GuildTips.isGuildCode(14999)).isTrue();
        assertThat(GuildTips.isGuildCode(15000)).isFalse();
    }

    @Test
    void 码互不相同_且不撞通用段() {
        Map<Integer, String> seen = new LinkedHashMap<>();
        guildCodes().forEach((name, code) -> {
            String previous = seen.put(code, name);
            assertThat(previous).as("tip 码 %d 被 %s 与 %s 同时使用", code, previous, name).isNull();
        });
        assertThat(GuildTips.SERVICE_UNAVAILABLE).isEqualTo(CommonErrorTip.common_error.kServiceUnavailable.getNumber());
        assertThat(GuildTips.FEATURE_UNAVAILABLE).isEqualTo(CommonErrorTip.common_error.kFeatureUnavailable.getNumber());
        assertThat(GuildTips.MESSAGE_ID_NOT_FOUND).isEqualTo(CommonErrorTip.common_error.kMessageIdNotFound.getNumber());
        for (int common : new int[] {GuildTips.SERVICE_UNAVAILABLE, GuildTips.FEATURE_UNAVAILABLE, GuildTips.MESSAGE_ID_NOT_FOUND}) {
            assertThat(GuildTips.isGuildCode(common)).isFalse();
            assertThat(seen).doesNotContainKey(common);
        }
    }

    /** 基线 fault 列只有 GuildIdGenUnavailable 标 1（faults.go:60）；Java 没有 fault 列，这里钉住写死的集合 = {14008}。 */
    @Test
    void 只有发号器不可用是故障() {
        guildCodes().forEach((name, code) ->
                assertThat(GuildTips.isFault(code)).as("%s = %d 的 fault 分类", name, code).isEqualTo(name.equals("ID_GEN_UNAVAILABLE")));
        assertThat(GuildTips.FAULTS).containsExactly(14008);
        assertThat(GuildTips.isFault(GuildTips.OK)).isFalse();
        assertThat(GuildTips.isFault(GuildTips.SERVICE_UNAVAILABLE)).as("信封 1003 另计 internal_error").isFalse();
        for (GuildTip tip : GuildTip.values()) {
            // 4.5：资产指令 op_id 发号失败同为 14008（economy_logic.go:386-401）
            assertThat(tip.fault()).as(tip.name())
                    .isEqualTo(tip == GuildTip.ID_GENERATOR_UNAVAILABLE || tip == GuildTip.ASSET_OP_ID_UNAVAILABLE);
        }
    }

    /** 每个码都必须直接写成生成枚举的 {@code _VALUE} 引用，不能手写数字、别名或引用其他码轴（同基线 TestNoHandWrittenTipCodes）。 */
    @Test
    void 不手写码值_常量与护栏表一一对应() throws IOException {
        Path source = Path.of("src/main/java/com/game/guild/rules/GuildTips.java");
        assertThat(source).exists();
        String text = Files.readString(source, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("static\\s+final\\s+int\\s+(\\w+)\\s*=\\s*([^;]+);").matcher(text);
        Pattern guildRef = Pattern.compile("GuildErrorTip\\.guild_error\\.kGuild\\w+_VALUE");
        Pattern commonRef = Pattern.compile("CommonErrorTip\\.common_error\\.k\\w+_VALUE");
        Set<String> seen = new HashSet<>();
        while (m.find()) {
            String name = m.group(1);
            if (SEGMENT_CONSTANTS.contains(name)) {
                continue;
            }
            seen.add(name);
            Pattern want = COMMON_CODES.contains(name) ? commonRef : guildRef;
            assertThat(want.matcher(m.group(2).trim()).matches())
                    .as("%s 必须直接写成生成枚举的 _VALUE 引用，实际是 %s", name, m.group(2).trim())
                    .isTrue();
        }
        Set<String> expected = new HashSet<>(guildCodes().keySet());
        expected.addAll(COMMON_CODES);
        expected.add("OK");
        assertThat(seen).as("GuildTips 的码常量与护栏表必须一一对应（新增码要同步纳入 guildCodes）")
                .containsExactlyInAnyOrderElementsOf(expected);

        Set<String> declared = new HashSet<>();
        for (Field f : GuildTips.class.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (f.getType() == int.class && Modifier.isStatic(mod) && Modifier.isFinal(mod)
                    && !SEGMENT_CONSTANTS.contains(f.getName())) {
                declared.add(f.getName());
            }
        }
        assertThat(declared).containsExactlyInAnyOrderElementsOf(expected);
    }

    // ================================================================ 原因串

    /** 每个发生点的码与英文原因串（§0.4；照抄基线，客户端不读但逐字节对拍需要）。新增 GuildTip 必须在这里登记。 */
    @Test
    void 每个发生点的码与原因串() {
        Map<GuildTip, Object[]> want = new EnumMap<>(GuildTip.class);
        want.put(GuildTip.ALREADY_IN_GUILD, new Object[] {14000, "already in a guild"});
        want.put(GuildTip.MEMBERSHIP_CHANGED, new Object[] {14000, "guild membership changed, retry"});
        want.put(GuildTip.GUILD_NOT_FOUND, new Object[] {14001, "guild not found"});
        want.put(GuildTip.APPLY_GUILD_ID_ZERO, new Object[] {14001, "guild id is zero"});
        want.put(GuildTip.NOT_IN_ANY_GUILD, new Object[] {14002, "not in any guild"});
        want.put(GuildTip.NOT_A_MEMBER, new Object[] {14002, "not a member of the guild"});
        want.put(GuildTip.GUILD_FULL, new Object[] {14003, "guild is full"});
        want.put(GuildTip.LEADER_CANT_LEAVE, new Object[] {14004, "leader cannot leave, disband or transfer instead"});
        want.put(GuildTip.NOT_GUILD_LEADER, new Object[] {14005, "not guild leader"});
        want.put(GuildTip.NO_PERMISSION, new Object[] {14006, "no permission"});
        want.put(GuildTip.ROLE_NOT_ASSIGNABLE, new Object[] {14006, "role not assignable"});
        want.put(GuildTip.NOT_RANKED, new Object[] {14007, "guild not ranked"});
        want.put(GuildTip.ID_GENERATOR_UNAVAILABLE, new Object[] {14008, "id generator unavailable"});
        want.put(GuildTip.INVALID_GUILD_NAME, new Object[] {14009, "invalid guild name"});
        want.put(GuildTip.NAME_TAKEN, new Object[] {14010, "guild name taken"});
        want.put(GuildTip.ANNOUNCEMENT_TOO_LONG, new Object[] {14011, "announcement too long"});
        want.put(GuildTip.HOME_ZONE_UNKNOWN, new Object[] {14012, "home zone unknown"});
        want.put(GuildTip.MERGE_FENCE_UNREADABLE, new Object[] {14013, "merge fence unreadable"});
        want.put(GuildTip.ZONE_MERGING, new Object[] {14013, "zone merging"});
        want.put(GuildTip.TARGET_ID_ZERO, new Object[] {14014, "target player id is zero"});
        want.put(GuildTip.TARGET_NOT_MEMBER, new Object[] {14014, "target is not a member"});
        want.put(GuildTip.CANNOT_TARGET_SELF, new Object[] {14015, "cannot target self"});
        want.put(GuildTip.CANNOT_REVIEW_OWN, new Object[] {14015, "cannot review own application"});
        want.put(GuildTip.RANK_TOO_LOW, new Object[] {14016, "rank too low"});
        want.put(GuildTip.OFFICER_RANK_REQUIRED, new Object[] {14016, "officer rank required"});
        want.put(GuildTip.OFFICER_LIMIT, new Object[] {14017, "officer limit reached"});
        want.put(GuildTip.APPLICATION_NOT_FOUND, new Object[] {14018, "application not found or expired"});
        want.put(GuildTip.CANCEL_GUILD_ID_ZERO, new Object[] {14018, "guild id is zero"});
        want.put(GuildTip.APPLICANT_ID_ZERO, new Object[] {14018, "applicant player id is zero"});
        want.put(GuildTip.APPLICANT_HOME_ZONE_UNKNOWN, new Object[] {14018, "applicant home zone unknown"});
        want.put(GuildTip.APPLICATION_LIMIT, new Object[] {14019, "pending application limit reached"});
        want.put(GuildTip.APPLICATION_QUEUE_FULL, new Object[] {14020, "guild application queue is full"});
        want.put(GuildTip.WRITE_CONFLICT, new Object[] {14021, "guild write conflict"});
        want.put(GuildTip.OVERLOADED, new Object[] {14021, "guild service overloaded"});
        // 4.5 经济段（guild-economy-spec §0.4）
        want.put(GuildTip.ASSET_OP_ID_UNAVAILABLE, new Object[] {14008, "asset op id generator unavailable"});
        want.put(GuildTip.FUNDS_INSUFFICIENT, new Object[] {14022, "guild funds insufficient"});
        want.put(GuildTip.MAX_LEVEL, new Object[] {14023, "guild already at max level"});
        want.put(GuildTip.DONATE_LIMIT, new Object[] {14024, "daily donate limit reached"});
        want.put(GuildTip.CURRENCY_INSUFFICIENT, new Object[] {14025, "currency insufficient"});
        want.put(GuildTip.ASSET_CHANNEL_DISABLED, new Object[] {14026, "guild asset channel disabled"});
        want.put(GuildTip.TOO_MANY_PENDING, new Object[] {14026, "too many pending asset ops"});
        want.put(GuildTip.DONATE_OPTION_NOT_FOUND, new Object[] {14027, "donate option not found"});
        want.put(GuildTip.ASSET_REJECTED, new Object[] {14027, "asset op rejected"});
        want.put(GuildTip.SHOP_GOODS_NOT_FOUND, new Object[] {14028, "shop goods not found"});
        want.put(GuildTip.GUILD_LEVEL_TOO_LOW, new Object[] {14029, "guild level too low"});
        want.put(GuildTip.COUNT_EXCEEDS_MAX_BUY, new Object[] {14030, "count exceeds max buy count"});
        want.put(GuildTip.SHOP_LIMIT, new Object[] {14030, "shop purchase limit reached"});
        want.put(GuildTip.CONTRIBUTION_INSUFFICIENT, new Object[] {14031, "contribution insufficient"});
        want.put(GuildTip.FEATURE_UNAVAILABLE, new Object[] {1006, "guild feature unavailable"});

        assertThat(want.keySet()).as("每个 GuildTip 都要登记").isEqualTo(EnumSet.allOf(GuildTip.class));
        want.forEach((tip, w) -> {
            assertThat(tip.code()).as(tip.name()).isEqualTo(w[0]);
            assertThat(tip.reason()).as(tip.name()).isEqualTo(w[1]);
            // 形状 = TipInfoMessage{id, parameters:[原因]}（guild_logic.go:120-122 tipErr）
            TipInfoMessage proto = tip.proto();
            assertThat(proto.getId()).isEqualTo(tip.code());
            assertThat(proto.getParametersList()).containsExactly(tip.reason());
        });
    }

    // ================================================================ mapWriteErr 整表

    @Test
    void 仓储拒绝到答复的整表() {
        Map<GuildReject, RejectReply> want = new EnumMap<>(GuildReject.class);
        // 别区的帮会与不存在的帮会同一答复；两者都按 cached 复核映射
        want.put(GuildReject.GUILD_GONE, tip(GuildTip.GUILD_NOT_FOUND, MappingRepair.VERIFY_AGAINST_CACHED));
        want.put(GuildReject.ZONE_MISMATCH, tip(GuildTip.GUILD_NOT_FOUND, MappingRepair.VERIFY_AGAINST_CACHED));
        want.put(GuildReject.NOT_MEMBER, tip(GuildTip.NOT_A_MEMBER, MappingRepair.VERIFY_AGAINST_CACHED));
        want.put(GuildReject.TARGET_NOT_MEMBER, tip(GuildTip.TARGET_NOT_MEMBER, MappingRepair.NONE));
        want.put(GuildReject.RANK_TOO_LOW, tip(GuildTip.RANK_TOO_LOW, MappingRepair.NONE));
        want.put(GuildReject.OFFICER_LIMIT, tip(GuildTip.OFFICER_LIMIT, MappingRepair.NONE));
        want.put(GuildReject.LEADER_CANT_LEAVE, tip(GuildTip.LEADER_CANT_LEAVE, MappingRepair.NONE));
        want.put(GuildReject.GUILD_FULL, tip(GuildTip.GUILD_FULL, MappingRepair.NONE));
        // 缓存说他没入帮、MySQL 说他入了：按 0 复核
        want.put(GuildReject.ALREADY_IN_GUILD, tip(GuildTip.ALREADY_IN_GUILD, MappingRepair.VERIFY_AGAINST_ZERO));
        // 基线在调用点映射（guild_logic.go:274-275、:544-546），Java 并进整表，结果相同
        want.put(GuildReject.NAME_TAKEN, tip(GuildTip.NAME_TAKEN, MappingRepair.NONE));
        want.put(GuildReject.ANNOUNCEMENT_FORBIDDEN, tip(GuildTip.NO_PERMISSION, MappingRepair.NONE));
        want.put(GuildReject.APPLICATION_NOT_FOUND, tip(GuildTip.APPLICATION_NOT_FOUND, MappingRepair.NONE));
        want.put(GuildReject.APPLICATION_LIMIT, tip(GuildTip.APPLICATION_LIMIT, MappingRepair.NONE));
        want.put(GuildReject.QUEUE_FULL, tip(GuildTip.APPLICATION_QUEUE_FULL, MappingRepair.NONE));
        want.put(GuildReject.ZONE_MERGING, tip(GuildTip.ZONE_MERGING, MappingRepair.NONE));
        want.put(GuildReject.WRITE_CONFLICT, tip(GuildTip.WRITE_CONFLICT, MappingRepair.NONE));
        // 双存储互相矛盾 / 配表缺行：故障（信封 1003）
        want.put(GuildReject.LEADER_MISMATCH, new RejectReply.Fault("guild data or configuration is inconsistent"));
        want.put(GuildReject.LEVEL_CONFIG_MISSING, new RejectReply.Fault("guild data or configuration is inconsistent"));
        // 4.5 经济哨兵（economyTip 整表，economy_logic.go:313-344；economy_logic_test.go:844）
        want.put(GuildReject.LEVEL_TOO_LOW, tip(GuildTip.GUILD_LEVEL_TOO_LOW, MappingRepair.NONE));
        want.put(GuildReject.DONATE_LIMIT, tip(GuildTip.DONATE_LIMIT, MappingRepair.NONE));
        want.put(GuildReject.SHOP_LIMIT, tip(GuildTip.SHOP_LIMIT, MappingRepair.NONE));
        want.put(GuildReject.CONTRIBUTION_INSUFFICIENT, tip(GuildTip.CONTRIBUTION_INSUFFICIENT, MappingRepair.NONE));
        want.put(GuildReject.MAX_LEVEL, tip(GuildTip.MAX_LEVEL, MappingRepair.NONE));
        want.put(GuildReject.FUNDS_INSUFFICIENT, tip(GuildTip.FUNDS_INSUFFICIENT, MappingRepair.NONE));
        want.put(GuildReject.TOO_MANY_PENDING, tip(GuildTip.TOO_MANY_PENDING, MappingRepair.NONE));

        assertThat(want.keySet()).as("每个 GuildReject 都要登记").isEqualTo(EnumSet.allOf(GuildReject.class));
        want.forEach((reject, reply) ->
                assertThat(GuildTips.forReject(reject)).as(reject.name()).isEqualTo(reply));

        // 基线单测的码表（guild_manage_logic_test.go:399-415）再核一遍码值
        assertThat(code(GuildReject.GUILD_GONE)).isEqualTo(GuildTips.GUILD_NOT_FOUND);
        assertThat(code(GuildReject.ZONE_MISMATCH)).isEqualTo(GuildTips.GUILD_NOT_FOUND);
        assertThat(code(GuildReject.NOT_MEMBER)).isEqualTo(GuildTips.NOT_IN_GUILD);
        assertThat(code(GuildReject.TARGET_NOT_MEMBER)).isEqualTo(GuildTips.TARGET_NOT_MEMBER);
        assertThat(code(GuildReject.RANK_TOO_LOW)).isEqualTo(GuildTips.RANK_TOO_LOW);
        assertThat(code(GuildReject.OFFICER_LIMIT)).isEqualTo(GuildTips.OFFICER_LIMIT);
        assertThat(code(GuildReject.LEADER_CANT_LEAVE)).isEqualTo(GuildTips.LEADER_CANT_LEAVE);
        assertThat(code(GuildReject.GUILD_FULL)).isEqualTo(GuildTips.GUILD_FULL);
        assertThat(code(GuildReject.ALREADY_IN_GUILD)).isEqualTo(GuildTips.ALREADY_IN_GUILD);
        assertThat(code(GuildReject.APPLICATION_NOT_FOUND)).isEqualTo(GuildTips.APPLICATION_NOT_FOUND);
        assertThat(code(GuildReject.APPLICATION_LIMIT)).isEqualTo(GuildTips.APPLICATION_LIMIT);
        assertThat(code(GuildReject.QUEUE_FULL)).isEqualTo(GuildTips.APPLICATION_QUEUE_FULL);
        assertThat(code(GuildReject.WRITE_CONFLICT)).isEqualTo(GuildTips.BUSY_RETRY);
        assertThat(code(GuildReject.ZONE_MERGING)).isEqualTo(GuildTips.ZONE_MERGING);
        assertThat(GuildTips.INCONSISTENT_REASON).isEqualTo("guild data or configuration is inconsistent");
        // 经济段码值（guild-economy-spec §3.0 的表）
        assertThat(code(GuildReject.LEVEL_TOO_LOW)).isEqualTo(GuildTips.SHOP_LEVEL_TOO_LOW).isEqualTo(14029);
        assertThat(code(GuildReject.DONATE_LIMIT)).isEqualTo(GuildTips.DONATE_LIMIT).isEqualTo(14024);
        assertThat(code(GuildReject.SHOP_LIMIT)).isEqualTo(GuildTips.SHOP_LIMIT).isEqualTo(14030);
        assertThat(code(GuildReject.CONTRIBUTION_INSUFFICIENT)).isEqualTo(GuildTips.CONTRIBUTION_INSUFFICIENT).isEqualTo(14031);
        assertThat(code(GuildReject.MAX_LEVEL)).isEqualTo(GuildTips.MAX_LEVEL).isEqualTo(14023);
        assertThat(code(GuildReject.FUNDS_INSUFFICIENT)).isEqualTo(GuildTips.FUNDS_INSUFFICIENT).isEqualTo(14022);
        assertThat(code(GuildReject.TOO_MANY_PENDING)).isEqualTo(GuildTips.ASSET_PENDING).isEqualTo(14026);
        // 经济哨兵的基线 errors.New 文本（guild_repo.go:71-83；assetop/types.go:252）
        assertThat(GuildReject.LEVEL_TOO_LOW.sentinel()).isEqualTo("guild level too low");
        assertThat(GuildReject.SHOP_LIMIT.sentinel()).isEqualTo("guild shop purchase limit reached");
        assertThat(GuildReject.CONTRIBUTION_INSUFFICIENT.sentinel()).isEqualTo("contribution balance insufficient");
        assertThat(GuildReject.TOO_MANY_PENDING.sentinel()).isEqualTo("assetop: too many pending ops for player stream");
    }

    /** 业务拒绝永不落到故障码上：in-band 回的码里没有 14008，也没有通用段的 1003。 */
    @Test
    void 拒绝答复的码都是业务码() {
        for (GuildReject reject : GuildReject.values()) {
            if (GuildTips.forReject(reject) instanceof RejectReply.Tip t) {
                assertThat(GuildTips.isFault(t.tip().code())).as(reject.name()).isFalse();
                assertThat(GuildTips.isGuildCode(t.tip().code())).as(reject.name()).isTrue();
            }
        }
    }

    /** 解散是唯一一处 RANK_TOO_LOW 不回 14016 的地方（guild_logic.go:491-498）；其余同整表。 */
    @Test
    void 解散的职位不足回14005() {
        assertThat(GuildTips.forDisbandReject(GuildReject.RANK_TOO_LOW))
                .isEqualTo(tip(GuildTip.NOT_GUILD_LEADER, MappingRepair.NONE));
        assertThat(GuildTip.NOT_GUILD_LEADER.code()).isEqualTo(GuildTips.NOT_LEADER);
        for (GuildReject reject : GuildReject.values()) {
            if (reject != GuildReject.RANK_TOO_LOW) {
                assertThat(GuildTips.forDisbandReject(reject)).as(reject.name()).isEqualTo(GuildTips.forReject(reject));
            }
        }
    }

    @Test
    void 哨兵文本照抄基线_互不相同() {
        Set<String> texts = new HashSet<>();
        for (GuildReject reject : GuildReject.values()) {
            assertThat(reject.sentinel()).as(reject.name()).isNotBlank();
            assertThat(texts.add(reject.sentinel())).as("哨兵文本重复：%s", reject.sentinel()).isTrue();
        }
        assertThat(GuildReject.WRITE_CONFLICT.sentinel())
                .isEqualTo("guild write conflict (deadlock retries exhausted or lock wait timeout)");
        assertThat(GuildReject.GUILD_GONE.sentinel()).isEqualTo("guild does not exist");
    }

    private static RejectReply tip(GuildTip tip, MappingRepair repair) {
        return new RejectReply.Tip(tip, repair);
    }

    private static int code(GuildReject reject) {
        return ((RejectReply.Tip) GuildTips.forReject(reject)).tip().code();
    }
}
