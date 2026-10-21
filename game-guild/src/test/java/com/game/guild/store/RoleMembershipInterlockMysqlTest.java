package com.game.guild.store;

import static com.game.guild.store.GuildMysqlFixture.*;
import static org.assertj.core.api.Assertions.*;
import com.game.api.proto.SessionContext;
import com.game.guild.rules.GuildRoles;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** 使用生产构造、原会话验证和真实player/G/S/M锁；与旧纯帮会协议夹具的资格替身分开。 */
@EnabledIfSystemProperty(named = "game.it.mysql", matches = ".+")
class RoleMembershipInterlockMysqlTest {
    private static SessionContext actor(long id) {
        return SessionContext.newBuilder().setPlayerId(id).setAccount("actor" + id).setOwnerRevision(1).build();
    }
    private static void player(GuildMysqlFixture db, long id) throws Exception {
        db.exec("INSERT INTO player(player_id,account,owner_revision,trade_listing_id) VALUES(?,?,1,0)", id, "actor" + id);
    }
    private static GuildMysqlFixture fixture() throws Exception {
        var db = GuildMysqlFixture.create();
        try {
            db.reset();
            player(db,11); player(db,22); return db;
        } catch (Exception failure) { db.close(); throw failure; }
    }
    @Test void 原修订和账号失效都不能建帮_冻结目标不能被迟到审批加入() throws Exception {
        try (var db = fixture()) {
            var store = new JdbcGuildStore(db.tx(GuildTxListener.NONE), GuildTxHooks.NONE);
            db.seedGuild(1, 7, 30, 11); db.seedApplication(1,22,NOW,NOW+TTL);
            try (var held = db.begin()) {
                execOn(held,"UPDATE player SET trade_listing_id=101 WHERE player_id=22");
                var review = CompletableFuture.supplyAsync(() -> store.forActor(actor(11)).reviewApplication(1,11,22,true,7,NOW,d()));
                held.commit();
                assertThatThrownBy(() -> review.get(10, TimeUnit.SECONDS)).hasRootCauseInstanceOf(SecurityException.class);
            }
            assertThat(db.memberships(22)).isZero();
            db.exec("UPDATE player SET trade_listing_id=0,owner_revision=3 WHERE player_id=22");
            var guild = new GuildData(2,"fresh",22,1,"",NOW,30,7,0,0,
                java.util.List.of(new GuildData.Member(22,GuildRoles.LEADER,NOW,NOW,0,0)));
            assertThatThrownBy(() -> store.forActor(actor(22)).createGuild(guild,d())).isInstanceOf(SecurityException.class);
            assertThat(db.guildRows(2)).isZero();
        }
    }
    @Test void 交叉审批先按player无符号顺序锁actor和applicant_没有P锁反序死锁() throws Exception {
        try (var db = fixture()) {
            var listener = new Recorder();
            var store = new JdbcGuildStore(db.tx(listener), GuildTxHooks.NONE);
            db.seedGuild(1,7,30,11); db.seedGuild(2,7,30,22);
            db.seedApplication(1,22,NOW,NOW+TTL); db.seedApplication(2,11,NOW,NOW+TTL);
            var results = concurrently(() -> store.forActor(actor(11)).reviewApplication(1,11,22,true,7,NOW,d()),
                () -> store.forActor(actor(22)).reviewApplication(2,22,11,true,7,NOW,d()));
            assertThat(db.memberships(11)).isEqualTo(1); assertThat(db.memberships(22)).isEqualTo(1);
            assertThat(results).hasSize(2);
            assertThat(results).allSatisfy(result -> assertThat(result.error()).isNull());
            assertThat(listener.deadlocks).isEmpty();
        }
    }
}
