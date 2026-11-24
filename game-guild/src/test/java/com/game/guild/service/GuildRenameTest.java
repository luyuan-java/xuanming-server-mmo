package com.game.guild.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.guild.rules.GuildReject;
import com.game.guild.rules.GuildRoles;
import com.game.guild.rules.GuildTableRules;
import com.game.guild.rules.GuildTips;
import com.game.guild.store.GuildData;
import com.game.guild.store.GuildStore.RenameOutcome;
import com.game.guild.store.GuildStore.Renamed;
import com.game.guild.store.TxOutcome;
import com.game.proto.content_guard.CheckTextResponse;
import com.game.proto.content_guard.TextCategory;
import com.game.proto.content_guard.TextScene;
import com.game.proto.content_guard.TextVerdict;
import com.game.proto.guild.CreateGuildRequest;
import com.game.proto.guild.CreateGuildResponse;
import com.game.proto.guild.GuildChangeKind;
import com.game.proto.guild.RenameGuildRequest;
import com.game.proto.guild.RenameGuildResponse;
import com.game.proto.guild.SetAnnouncementRequest;
import com.game.proto.guild.SetAnnouncementResponse;
import com.game.table.GuildLevelTable;
import com.game.table.GuildRuleTable;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 帮会改名（439）与内容安全接入（建帮 / 公告 / 改名）单元测试（对齐 Go guild_rename_logic_test / content_check_test）。
 */
final class GuildRenameTest {

    private static final long LEADER = 42L;
    private static final long MEMBER = 77L;
    private static final long GUILD_ID = 9001L;

    private final GuildServiceFixture f = new GuildServiceFixture();

    @AfterEach
    void close() {
        f.close();
    }

    private GuildService serviceWithGuard(GuildService.TextGuard guard) {
        return new GuildService(f.store, f.cache, f.access, f.views, f.ranks, f.guildPushes,
                () -> f.guildIds.getAsLong(), new GuildTableLookup() {
                    @Override public com.game.guild.rules.GuildTableRules.ApplicationRules applicationRules() { return f.rules; }
                    @Override public java.util.OptionalInt officerCap(int level) { return java.util.OptionalInt.of(GuildServiceFixture.MAX_OFFICERS); }
                    @Override public java.util.OptionalInt initialMaxMembers() { return f.initialMaxMembers; }
                    @Override public com.game.guild.rules.GuildTableRules.LevelDisplay levelDisplay(int level) { return f.levelDisplay; }
                }, () -> GuildServiceFixture.NOW, guard);
    }

    @Test
    void renameGuildSuccessInvalidatesCacheAndPushesRenamedToOtherMembers() {
        f.zones.put(LEADER, GuildServiceFixture.ZONE);
        long nextAllowedMs = GuildServiceFixture.NOW + 7L * 86_400_000L;
        GuildData updated = new GuildData(GUILD_ID, "清风阁", LEADER, 2, "", GuildServiceFixture.NOW - 10_000L, 30,
                GuildServiceFixture.ZONE, 0, 500, List.of(
                        new GuildData.Member(LEADER, GuildRoles.LEADER, 1, 1, 0, 0),
                        new GuildData.Member(MEMBER, GuildRoles.MEMBER, 2, 2, 0, 0)));
        f.store.rename = (gid, name) -> new RenameOutcome(TxOutcome.ok(new Renamed(updated, LEADER, nextAllowedMs)), nextAllowedMs);

        AtomicReference<TextScene> seenScene = new AtomicReference<>();
        GuildService svc = serviceWithGuard((scene, pid, text) -> {
            seenScene.set(scene);
            return CheckTextResponse.newBuilder().setVerdict(TextVerdict.TEXT_VERDICT_PASS).build();
        });

        RenameGuildResponse resp = svc.renameGuild(LEADER,
                RenameGuildRequest.newBuilder().setGuildId(GUILD_ID).setName("  清风阁 ").build(),
                Deadline.after(3_000));
        assertThat(resp.hasErrorMessage()).isFalse();
        assertThat(resp.getGuild().getName()).isEqualTo("清风阁");
        assertThat(resp.getNextRenameTime()).isEqualTo(nextAllowedMs / 1_000L);
        assertThat(seenScene.get()).isEqualTo(TextScene.TEXT_SCENE_GUILD_NAME);
        assertThat(f.pushes).hasSize(1);
        assertThat(f.pushes.getFirst().change().getKind()).isEqualTo(GuildChangeKind.GUILD_CHANGE_KIND_RENAMED);
        assertThat(f.pushes.getFirst().recipients()).containsExactly(MEMBER);
    }

    @Test
    void renameGuildMapsCooldownNotLeaderSameNameAndValidationErrors() {
        f.zones.put(LEADER, GuildServiceFixture.ZONE);

        // guild_id = 0 -> 14001
        assertThat(f.guilds.renameGuild(LEADER, RenameGuildRequest.newBuilder().setName("新帮名").build(),
                Deadline.after(3_000)).getErrorMessage().getId()).isEqualTo(GuildTips.GUILD_NOT_FOUND);

        // 非法帮名 -> 14009
        assertThat(f.guilds.renameGuild(LEADER, RenameGuildRequest.newBuilder().setGuildId(GUILD_ID).setName("   ").build(),
                Deadline.after(3_000)).getErrorMessage().getId()).isEqualTo(GuildTips.NAME_INVALID);

        // 冷却中 -> 14046 且携带 next_rename_time
        long nextMs = GuildServiceFixture.NOW + 3600_000L;
        f.store.rename = (gid, name) -> new RenameOutcome(TxOutcome.reject(GuildReject.RENAME_COOLDOWN), nextMs);
        RenameGuildResponse cd = f.guilds.renameGuild(LEADER,
                RenameGuildRequest.newBuilder().setGuildId(GUILD_ID).setName("清风阁").build(), Deadline.after(3_000));
        assertThat(cd.getErrorMessage().getId()).isEqualTo(GuildTips.RENAME_COOLDOWN);
        assertThat(cd.getNextRenameTime()).isEqualTo(nextMs / 1_000L);

        // 非帮主 -> 14005
        f.store.rename = (gid, name) -> new RenameOutcome(TxOutcome.reject(GuildReject.RANK_TOO_LOW), 0L);
        assertThat(f.guilds.renameGuild(LEADER,
                RenameGuildRequest.newBuilder().setGuildId(GUILD_ID).setName("清风阁").build(), Deadline.after(3_000))
                .getErrorMessage().getId()).isEqualTo(GuildTips.NOT_LEADER);

        // 同名 -> 14047
        f.store.rename = (gid, name) -> new RenameOutcome(TxOutcome.reject(GuildReject.RENAME_SAME_NAME), 0L);
        assertThat(f.guilds.renameGuild(LEADER,
                RenameGuildRequest.newBuilder().setGuildId(GUILD_ID).setName("清风阁").build(), Deadline.after(3_000))
                .getErrorMessage().getId()).isEqualTo(GuildTips.RENAME_SAME_NAME);
    }

    @Test
    void contentGuardRefusesSensitiveAndUnavailableOnCreateAnnouncementAndRename() {
        f.zones.put(LEADER, GuildServiceFixture.ZONE);

        // MASKED 或 REJECT 在名字/公告场景均 fail-closed 拒绝
        GuildService maskedSvc = serviceWithGuard((scene, pid, text) ->
                CheckTextResponse.newBuilder().setVerdict(TextVerdict.TEXT_VERDICT_MASKED)
                        .setCategory(TextCategory.TEXT_CATEGORY_ABUSE).build());
        CreateGuildResponse cr = maskedSvc.createGuild(LEADER,
                CreateGuildRequest.newBuilder().setName("测试帮").build(), Deadline.after(3_000));
        assertThat(cr.getErrorMessage().getId()).isEqualTo(GuildTips.NAME_SENSITIVE);

        SetAnnouncementResponse ann = maskedSvc.setAnnouncement(LEADER,
                SetAnnouncementRequest.newBuilder().setGuildId(GUILD_ID).setAnnouncement("公告内容").build(),
                Deadline.after(3_000));
        assertThat(ann.getErrorMessage().getId()).isEqualTo(GuildTips.ANNOUNCEMENT_SENSITIVE);

        // 内容安全不可达 -> 14045（不碰库）
        GuildService downSvc = serviceWithGuard((scene, pid, text) -> {
            throw new IllegalStateException("content guard timeout");
        });
        RenameGuildResponse rn = downSvc.renameGuild(LEADER,
                RenameGuildRequest.newBuilder().setGuildId(GUILD_ID).setName("清风阁").build(), Deadline.after(3_000));
        assertThat(rn.getErrorMessage().getId()).isEqualTo(GuildTips.TEXT_CHECK_UNAVAILABLE);
    }

    @Test
    void tableRulesRejectOutOfRangeRenameFields() {
        GuildLevelTable lv1 = GuildLevelTable.newBuilder().setId(1).setMaxMembers(30).setMaxOfficers(3).setUpgradeCostFunds(0).build();
        GuildRuleTable badCooldown = GuildRuleTable.newBuilder().setId(1)
                .setApplicationExpireHours(72).setMaxPendingApplicationsPerPlayer(3).setMaxPendingApplicationsPerGuild(50)
                .setRenameCooldownSeconds((int) GuildTableRules.MAX_RENAME_DURATION_SECONDS + 1).build();
        assertThatThrownBy(() -> GuildTableRules.validate(badCooldown, List.of(lv1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rename_cooldown_seconds");

        GuildRuleTable badCost = GuildRuleTable.newBuilder().setId(1)
                .setApplicationExpireHours(72).setMaxPendingApplicationsPerPlayer(3).setMaxPendingApplicationsPerGuild(50)
                .setRenameCostFunds(GuildTableRules.MAX_RENAME_COST_FUNDS + 1).build();
        assertThatThrownBy(() -> GuildTableRules.validate(badCost, List.of(lv1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rename_cost_funds");
    }
}
