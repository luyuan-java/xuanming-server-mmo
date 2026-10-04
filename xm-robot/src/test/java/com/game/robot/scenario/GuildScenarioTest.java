package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import com.game.proto.ClientRequest;
import com.game.proto.TipInfoMessage;
import com.game.proto.guild.CreateGuildResponse;
import com.game.proto.guild.GuildChangeKind;
import com.game.proto.guild.GuildChangedS2C;
import com.game.proto.guild.GuildInfo;
import com.game.proto.guild.GuildMember;
import com.game.proto.guild.LeaveGuildResponse;
import com.game.proto.guild.SetAnnouncementRequest;
import com.game.robot.RobotOptions;
import com.game.table.ConfigTables;
import com.google.protobuf.Descriptors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** guild 场景里不连服务端就能钉住的部分：节拍与 gate 限频、规范化撞名用例、快照判定、无符号输出、应答形状、账号与子命令。 */
class GuildScenarioTest {

    /** 大于 Long.MAX_VALUE 的玩家号（无符号 uint64），输出与比较都不能按有符号处理。 */
    private static final long BIG = 0x8000_0000_0000_0001L;
    private static final long MS = 1_000_000L;

    @Test
    void 节拍_相邻请求至少隔300毫秒_不同消息号互不退火() {
        GuildScenario.Pacer pacer = pacer();
        assertThat(pacer.delayNanos(15, 0)).as("第一次不用等").isZero();
        pacer.record(15, 0);
        assertThat(pacer.delayNanos(35, 100 * MS)).isEqualTo(200 * MS);
        pacer.record(35, 300 * MS);
        pacer.record(29, 600 * MS);
        pacer.record(38, 900 * MS);
        assertThat(pacer.delayNanos(39, 1200 * MS)).as("四个不同的号：只受间隔约束").isZero();
    }

    @Test
    void 节拍_同号第4次等最早那次滑出1100毫秒窗口() {
        GuildScenario.Pacer pacer = pacer();
        pacer.record(217, 0);
        pacer.record(217, 300 * MS);
        pacer.record(217, 600 * MS);
        assertThat(pacer.delayNanos(217, 900 * MS)).isEqualTo(200 * MS);
        pacer.record(217, 1100 * MS);
        assertThat(pacer.delayNanos(217, 1400 * MS)).as("窗口里最早的变成 300 ms 那次").isEqualTo(0);
        assertThat(pacer.delayNanos(19, 1300 * MS)).as("别的号只等间隔").isEqualTo(100 * MS);
    }

    @Test
    void 节拍_按节拍连发同一个号_任意1秒内不超过3条() {
        // gate 按会话、按消息号滑动窗口限频（MessageRateLimiter）：表外号（8 / 220 / 239–243）缺省每秒 3 条、帮会写 5 条、读 10 条
        GuildScenario.Pacer pacer = pacer();
        List<Long> sent = new ArrayList<>();
        long now = 0;
        for (int i = 0; i < 20; i++) {
            now += pacer.delayNanos(223, now);
            pacer.record(223, now);
            sent.add(now);
            now += 7 * MS; // 往返耗时
        }
        for (long start : sent) {
            long inWindow = sent.stream().filter(t -> t >= start && t - start < 1000 * MS).count();
            assertThat(inWindow).as("从 %d ms 起 1 秒内", start / MS).isLessThanOrEqualTo(GuildScenario.SAME_ID_MAX_IN_WINDOW);
        }
        assertThat(GuildScenario.SAME_ID_MAX_IN_WINDOW).isLessThanOrEqualTo(3);
        assertThat(GuildScenario.SAME_ID_WINDOW).isGreaterThan(Duration.ofSeconds(1));
    }

    @Test
    void 全角大写的帮名按NFKC加逐码点小写与原名同键_原串不同() {
        String nonce = GuildScenario.nonce();
        assertThat(nonce).matches("[0-9a-f]{8}");
        String original = "烟测" + nonce;
        String fullWidth = "烟测" + GuildScenario.fullWidthUpper(nonce);
        assertThat(fullWidth).isNotEqualTo(original);
        assertThat(fullWidth.codePointCount(0, fullWidth.length())).isEqualTo(original.codePointCount(0, original.length()));
        assertThat(nameKey(fullWidth)).isEqualTo(nameKey(original)).isEqualTo(original);
        assertThat(GuildScenario.fullWidthUpper("0a9fZ")).isEqualTo("０Ａ９ＦＺ");
    }

    @Test
    void 超长公告大于服务端600字节_整包仍小于gate的1KB() {
        SetAnnouncementRequest body = SetAnnouncementRequest.newBuilder().setGuildId(-1L).setPlayerId(-1L)
                .setAnnouncement("x".repeat(GuildScenario.OVERSIZE_ANNOUNCEMENT_BYTES)).build();
        ClientRequest frame = ClientRequest.newBuilder().setId(Long.MAX_VALUE).setMessageId(Integer.MAX_VALUE)
                .setBody(body.toByteString()).build();
        assertThat(GuildScenario.OVERSIZE_ANNOUNCEMENT_BYTES).isGreaterThan(600);
        assertThat(frame.getSerializedSize()).isLessThan(1024);
    }

    @Test
    void 第1级长老位上限与同步来的GuildLevel表一致() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables")) ? Path.of("../config-data/tables") : Path.of("config-data/tables");
        ConfigTables tables = ConfigTables.load(dir);
        assertThat(tables.guildLevel().get(1).getMaxOfficers()).isEqualTo(GuildScenario.LEVEL1_MAX_OFFICERS);
    }

    @Test
    void 每个有应答的GuildService方法第1个字段都是error_message_tip按反射取() {
        MessageIdRegistry registry = MessageIdRegistry.loadFromClasspath();
        List<MessageMethod> guild = registry.all().stream().filter(m -> m.serviceName().equals("GuildService")).toList();
        assertThat(guild).hasSize(28);
        for (MessageMethod method : guild) {
            Descriptors.Descriptor response = method.responsePrototype().getDescriptorForType();
            if (method.methodName().equals("NotifyGuildChanged")) {
                assertThat(response.getFullName()).isEqualTo("Empty");
                continue;
            }
            Descriptors.FieldDescriptor field = response.findFieldByName("error_message");
            assertThat(field).as(method.key()).isNotNull();
            assertThat(field.getNumber()).as(method.key()).isEqualTo(1);
            assertThat(field.getMessageType()).as(method.key()).isEqualTo(TipInfoMessage.getDescriptor());
        }
        CreateGuildResponse withTip = CreateGuildResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(14010).addParameters("guild name taken")).build();
        assertThat(GuildScenario.tipOf(withTip)).isEqualTo(14010);
        assertThat(GuildScenario.describeTip(withTip)).isEqualTo("tip=14010 parameters=[guild name taken]");
        assertThat(GuildScenario.tipOf(LeaveGuildResponse.getDefaultInstance())).isZero();
        assertThat(GuildScenario.describeTip(LeaveGuildResponse.getDefaultInstance())).isEqualTo("tip=0");
    }

    @Test
    void 长老判定同时查role_长老数与长老位上限() {
        GuildInfo ok = guild(BIG, member(BIG, GuildScenario.ROLE_LEADER), member(7, GuildScenario.ROLE_OFFICER))
                .toBuilder().setOfficerCount(1).setMaxOfficers(GuildScenario.LEVEL1_MAX_OFFICERS).build();
        assertThat(GuildScenario.officerProblem(ok, 7)).isEmpty();
        assertThat(GuildScenario.officerProblem(ok.toBuilder().setOfficerCount(2).build(), 7)).contains("officer_count=2");
        assertThat(GuildScenario.officerProblem(ok.toBuilder().setMaxOfficers(3).build(), 7)).contains("max_officers=3");
        assertThat(GuildScenario.officerProblem(ok, 8)).contains("role=-1");
        assertThat(GuildScenario.officerProblem(ok, BIG)).contains("9223372036854775809", "role=3");
    }

    @Test
    void 转让判定_leader_id与成员表一致_原帮主降为长老() {
        GuildInfo transferred = guild(7, member(7, GuildScenario.ROLE_LEADER), member(BIG, GuildScenario.ROLE_OFFICER));
        assertThat(GuildScenario.leadershipProblem(transferred, 7, BIG)).isEmpty();
        assertThat(GuildScenario.leadershipProblem(transferred.toBuilder().setLeaderId(BIG).build(), 7, BIG))
                .isEqualTo("leader_id=9223372036854775809，期望 7");
        GuildInfo splitBrain = guild(7, member(7, GuildScenario.ROLE_OFFICER), member(BIG, GuildScenario.ROLE_LEADER));
        assertThat(GuildScenario.leadershipProblem(splitBrain, 7, BIG)).contains("新帮主 7 在册 role=1");
        GuildInfo demotedToMember = guild(7, member(7, GuildScenario.ROLE_LEADER), member(BIG, GuildScenario.ROLE_MEMBER));
        assertThat(GuildScenario.leadershipProblem(demotedToMember, 7, BIG)).contains("原帮主 9223372036854775809 在册 role=0");
    }

    @Test
    void 成员名判定与申请列表查找_号按无符号() {
        GuildInfo guild = guild(BIG, member(BIG, GuildScenario.ROLE_LEADER).toBuilder().setName("甲").build(), member(-2L, 0));
        assertThat(GuildScenario.unnamedMembers(guild)).containsExactly("18446744073709551614");
        assertThat(GuildScenario.roleOf(guild, -2L)).isEqualTo(GuildScenario.ROLE_MEMBER);
        assertThat(GuildScenario.roleOf(guild, 3)).isEqualTo(-1);
        assertThat(GuildScenario.hasApplication(List.of(com.game.proto.guild.GuildApplicationView.newBuilder().setGuildId(BIG).build()),
                BIG)).isTrue();
        assertThat(GuildScenario.hasApplicant(List.of(com.game.proto.guild.GuildApplicantView.newBuilder().setPlayerId(BIG).build()),
                BIG + 1)).isFalse();
    }

    @Test
    void 摘要里的号按无符号十进制() {
        String text = GuildScenario.describe(guild(BIG, member(BIG, GuildScenario.ROLE_LEADER)).toBuilder().setGuildId(-1L)
                .setZoneId(1).setPendingApplicationCount(2).build());
        assertThat(text).contains("guild_id=18446744073709551615", "leader=9223372036854775809", "pending=2",
                "members=[9223372036854775809:3]").doesNotContain("-");
        assertThat(GuildScenario.describe(GuildChangedS2C.newBuilder().setKind(GuildChangeKind.GUILD_CHANGE_KIND_MEMBER_KICKED)
                .setGuildId(-1L).setActorPlayerId(BIG).build()))
                .isEqualTo("GUILD_CHANGE_KIND_MEMBER_KICKED(guild=18446744073709551615 actor=9223372036854775809 target=0)");
    }

    @Test
    void guild子命令_账号带gd标签_五个账号等长() throws Exception {
        RobotOptions options = RobotOptions.parse(List.of("guild", "--run-tag", "x1"), Map.of(RobotOptions.PASSWORD_ENV, "p"), 0);
        assertThat(options.scenario()).isEqualTo(RobotOptions.Scenario.GUILD);
        String a = GuildScenario.accountName(options.accountPrefix(), options.runTag(), "a");
        assertThat(a).isEqualTo("robot_java_gdx1_a");
        for (String suffix : List.of("b", "d", "e", "f")) {
            assertThat(GuildScenario.accountName(options.accountPrefix(), options.runTag(), suffix)).hasSize(a.length());
        }
        assertThat(RobotOptions.usage()).contains("|guild|", "  guild ");
    }

    // ================================================================ 夹具

    private static GuildScenario.Pacer pacer() {
        return new GuildScenario.Pacer(GuildScenario.REQUEST_SPACING, GuildScenario.SAME_ID_WINDOW,
                GuildScenario.SAME_ID_MAX_IN_WINDOW);
    }

    /** 与基线 name_norm 同义的判重键（guild-spec §2.4，只用于本测试；字符集只含 ASCII 与 CJK，逐码点小写与 Go 一致）。 */
    private static String nameKey(String display) {
        String nfkc = Normalizer.normalize(display.strip(), Normalizer.Form.NFKC).strip();
        StringBuilder out = new StringBuilder();
        nfkc.codePoints().map(Character::toLowerCase).forEach(out::appendCodePoint);
        return out.toString();
    }

    private static GuildMember member(long playerId, int role) {
        return GuildMember.newBuilder().setPlayerId(playerId).setRole(role).build();
    }

    private static GuildInfo guild(long leader, GuildMember... members) {
        return GuildInfo.newBuilder().setGuildId(9).setLeaderId(leader).addAllMembers(List.of(members)).build();
    }
}
