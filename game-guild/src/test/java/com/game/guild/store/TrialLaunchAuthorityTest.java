package com.game.guild.store;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.proto.guild.*;
import com.google.protobuf.ByteString;
import java.math.BigInteger;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** 私有同意和不可变原件的纯接口回归；连接替身只观察P首锁，不冒充真实SQL并发证据。 */
class TrialLaunchAuthorityTest {
    private org.mockito.MockedStatic<com.game.common.player.PlayerRouteAuthority> route;
    @org.junit.jupiter.api.BeforeEach void isolateRoutePrefix()throws Exception{
        // 本类连接只观察原owner/P顺序；route完整SQL前缀由PlayerRouteAuthoritySqlTest验证。
        route=mockStatic(com.game.common.player.PlayerRouteAuthority.class,CALLS_REAL_METHODS);
        route.when(()->com.game.common.player.PlayerRouteAuthority.requireMeta(any(Connection.class),anyInt(),anyBoolean())).thenAnswer(call->null);
        route.when(()->com.game.common.player.PlayerRouteAuthority.lock(any(Connection.class),org.mockito.ArgumentMatchers.<Collection<Long>>any(),anyInt())).thenAnswer(call->null);
    }
    @org.junit.jupiter.api.AfterEach void closeRoutePrefix(){route.close();}
    private static GuildTrialRoleIdentity identity(long player) {
        return GuildTrialRoleIdentity.newBuilder().setPlayerId(player).setAccount("account-" + Long.toUnsignedString(player))
                .setOwnerRevision(7).build();
    }
    private static GuildTrialLaunchOriginal original() {
        return GuildTrialLaunchOriginal.newBuilder().setRequestId(101).setGuildId(11).setActivityId(3).setPeriodKey(8)
                .setGuildPeriodKey(8).setInitiatorPlayerId(-1L).setDungeonId(7).setCreatedMs(1234)
                .addRoster(identity(7)).addRoster(identity(-1L)).build();
    }
    private static GuildTrialLaunchRecord record(GuildTrialLaunchOriginal original) throws SQLException {
        byte[] raw = TrialLaunchAuthority.bytes(original);
        return GuildTrialLaunchRecord.newBuilder().setRequestId(original.getRequestId()).setState(1)
                .setOriginalProto(ByteString.copyFrom(raw)).setRequestDigest(ByteString.copyFrom(TrialLaunchAuthority.digest(raw)))
                .setCreatedMs(original.getCreatedMs()).setUpdatedMs(original.getCreatedMs()).build();
    }

    @Test void 本人同意以标准PB分帧保存_拒跨房间重复成员截断及旧修订() throws Exception {
        var identities = List.of(identity(7), identity(-1L));
        byte[] raw = TrialLaunchAuthority.consentBytes(101, identities);
        assertEquals(new HashSet<>(identities), new HashSet<>(TrialLaunchAuthority.consents(raw,101).values()));
        assertThrows(SQLException.class, () -> TrialLaunchAuthority.consents(raw,102));
        assertThrows(SQLException.class, () -> TrialLaunchAuthority.consents(Arrays.copyOf(raw,raw.length-1),101));
        assertThrows(SQLException.class, () -> TrialLaunchAuthority.consents(TrialLaunchAuthority.consentBytes(101,List.of(identity(7),identity(7))),101));
        assertThrows(SQLException.class, () -> TrialLaunchAuthority.consents(new byte[65537],101));
        assertThrows(SecurityException.class, () -> TrialLaunchAuthority.identity(SessionContext.newBuilder().setPlayerId(7)
                .setAccount("account-7").setOwnerRevision(0).build(),7));
        assertThrows(SecurityException.class, () -> TrialLaunchAuthority.identity(SessionContext.newBuilder().setPlayerId(8)
                .setAccount("account-7").setOwnerRevision(7).build(),7));
        assertFalse(GuildTrialLobbyView.getDescriptor().getFields().stream().anyMatch(f -> f.getName().contains("account")
                || f.getName().contains("owner_revision")));
    }

    @Test void 永久原件核摘要身份与状态_重建Start保留原名单顺序与上下文() throws Exception {
        var original = original(); var record = record(original);
        assertEquals(original,TrialLaunchAuthority.original(record));
        assertNotEquals(original.getInitiatorPlayerId(),original.getRoster(0).getPlayerId());
        var start = TrialLaunchAuthority.start(TrialLaunchAuthority.original(record));
        assertEquals(List.of(-1L,7L),start.getMemberPlayerIdsList());
        assertEquals(101,start.getActivityContext().getLaunchRequestId());
        assertEquals(7,start.getBattleConfigId());
        assertThrows(SQLException.class, () -> TrialLaunchAuthority.original(record.toBuilder().setRequestDigest(ByteString.EMPTY).build()));
        assertThrows(SQLException.class, () -> TrialLaunchAuthority.original(record.toBuilder().setState(2).build()));
        assertThrows(SQLException.class, () -> TrialLaunchAuthority.original(record.toBuilder().setCreatedMs(1235).build()));
        assertThrows(SQLException.class, () -> TrialLaunchAuthority.original(record(original.toBuilder().addRoster(identity(7)).build())));
        assertThrows(SQLException.class, () -> TrialLaunchAuthority.original(record(original.toBuilder().setRoster(1,identity(-1L)
                .toBuilder().setOwnerRevision(Long.MIN_VALUE)).build())));
        assertThrows(SQLException.class,()->TrialLaunchAuthority.original(record(original.toBuilder().setInitiatorPlayerId(8).build())));
        assertThrows(SQLException.class,()->TrialLaunchAuthority.original(record(original.toBuilder().clearRoster().addRoster(identity(-1L)).addRoster(identity(7)).build())));
    }

    @Test void 多人P锁使用uint64升序_发起人在最高号也不抢首锁() throws Exception {
        var locked = new ArrayList<String>();
        Connection connection = mock(Connection.class);
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            String query = call.getArgument(0);
            PreparedStatement statement = mock(PreparedStatement.class);
            var parameter = new AtomicReference<Object>();
            doAnswer(set -> { if ((Integer)set.getArgument(0) == 1) parameter.set(set.getArgument(1)); return null; })
                    .when(statement).setObject(anyInt(),any());
            when(statement.executeQuery()).thenAnswer(execute -> {
                Object value = parameter.get();
                String player = value instanceof BigInteger big ? big.toString() : Long.toUnsignedString(((Number)value).longValue());
                locked.add(player);
                ResultSet row = mock(ResultSet.class); when(row.next()).thenReturn(true,false);
                if (query.startsWith("SELECT account,")) {
                    when(row.getString(1)).thenReturn("account-" + player);
                    when(row.getString(2)).thenReturn("7"); when(row.getString(3)).thenReturn("0");
                } else when(row.getLong(1)).thenReturn(0L);
                return row;
            });
            return statement;
        });
        var actor = SessionContext.newBuilder().setPlayerId(-1L).setAccount("account-18446744073709551615").setOwnerRevision(7).build();
        new AuthorityJdbc(source).tx(Deadline.after(5000), sql -> {
            TrialLaunchAuthority.lockPlayers(sql,List.of(-1L,Long.MIN_VALUE,7L),actor);
            return null;
        });
        assertEquals(List.of("7","9223372036854775808","18446744073709551615"),locked);
    }
}
