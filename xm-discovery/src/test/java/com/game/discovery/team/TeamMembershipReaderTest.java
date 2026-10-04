package com.game.discovery.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.discovery.RedisKeys;
import com.game.discovery.proto.TeamInfo;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link TeamMembershipReader} 的回复解析（不连 Redis）：三态判定同基线 ParseTeamIndexReply（player_team.h:79-108）。 */
class TeamMembershipReaderTest {

    private static final long PLAYER = 42;

    private static byte[] s(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static List<Object> reply(String tid, String epoch, long hasInfo, byte[] info) {
        return Arrays.asList(s(tid), s(epoch), hasInfo, info);
    }

    @Test
    void 投影键与索引键同一hash_tag_前缀由RedisKeys推出() {
        assertThat(TeamMembershipReader.INFO_KEY_PREFIX).isEqualTo("xm:{team}:info:");
        assertThat(RedisKeys.teamPlayer(1)).startsWith("xm:{team}:");
    }

    @Test
    void 两个字段都缺失_键缺失() {
        assertThat(TeamMembershipReader.parse(PLAYER, reply("", "", 0, new byte[0]))).isEqualTo(TeamMembership.KEY_MISSING);
    }

    @Test
    void tid为0_无队() {
        assertThat(TeamMembershipReader.parse(PLAYER, reply("0", "18446744073709551615", 0, new byte[0])))
                .isEqualTo(new TeamMembership(0, -1L, false, null));
    }

    @Test
    void 投影缺失与正常() {
        assertThat(TeamMembershipReader.parse(PLAYER, reply("9223372036854775808", "1", 0, new byte[0])))
                .isEqualTo(new TeamMembership(Long.MIN_VALUE, 1, false, null));
        TeamInfo info = TeamInfo.newBuilder().setTeamId(5).setLeaderId(PLAYER).addMembers(PLAYER).build();
        assertThat(TeamMembershipReader.parse(PLAYER, reply("5", "2", 1, info.toByteArray())))
                .isEqualTo(new TeamMembership(5, 2, false, info));
    }

    @Test
    void 形状或数字不对_一律当损坏() {
        List<List<Object>> bad = List.of(
                reply("5", "", 0, new byte[0]),                       // 半个 hash
                reply("", "5", 0, new byte[0]),
                reply("+5", "1", 0, new byte[0]),                     // 带符号
                reply("05", "1", 0, new byte[0]),                     // 前导零
                reply("5", "1x", 0, new byte[0]),
                reply("18446744073709551616", "1", 0, new byte[0]),   // 溢出 uint64
                reply("5", "1", 1, new byte[] {(byte) 0xff}),         // 投影解不开
                List.of(s("5"), s("1"), 0L));                         // 少一项
        for (List<Object> r : bad) {
            assertThatThrownBy(() -> TeamMembershipReader.parse(PLAYER, r)).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> TeamMembershipReader.parse(PLAYER, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 记录不变量() {
        assertThatThrownBy(() -> new TeamMembership(1, 0, true, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TeamMembership(0, 1, false, TeamInfo.getDefaultInstance()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new TeamMembership(3, 1, false, null).projectionMissing()).isTrue();
        assertThat(TeamMembership.KEY_MISSING.inTeam()).isFalse();
    }
}
