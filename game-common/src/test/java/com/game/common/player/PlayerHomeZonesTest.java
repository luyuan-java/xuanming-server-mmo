package com.game.common.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.common.player.PlayerProfiles.Profile;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 归属区 = player.zone_id（team D5、guild D3、trade T3）。原 game-team PlayerTableHomeZonesTest 的用例上移到这里
 * （基线 homezone_test / service_test.go:305 TestDataServiceHomeZone、trade home_zone_test.go 的 Java 对应）。
 */
class PlayerHomeZonesTest {

    /** 大于 Long.MAX_VALUE 的玩家号（uint64 位模式）。 */
    private static final long BIG = 0x8000_0000_0000_0001L;

    private static Profile profile(long id, int zone) {
        return new Profile(id, "n", 1, 1, 0, "", zone);
    }

    @Test
    void 缺行或zone为0都是缺项_交给调用方回归属区未确认() {
        HomeZones zones = new PlayerHomeZones((ids, d) -> Map.of(1L, profile(1, 3), 2L, profile(2, 0)),
                Duration.ofMillis(1500));
        Map<Long, Integer> got = zones.homeZones(List.of(1L, 2L, 3L), Deadline.after(3500));
        assertThat(got).containsExactly(Map.entry(1L, 3));
    }

    @Test
    void 查询失败原样抛出_交给调用方按故障处理() {
        HomeZones down = new PlayerHomeZones((ids, d) -> {
            throw new DependencyException("读玩家资料失败");
        });
        assertThatThrownBy(() -> down.homeZones(List.of(1L), Deadline.after(3500))).isInstanceOf(DependencyException.class);
        assertThatThrownBy(() -> down.homeZoneOf(1L, Deadline.after(3500))).isInstanceOf(DependencyException.class);
    }

    @Test
    void 查询预算取单次超时与请求剩余预算的较小者() {
        AtomicLong seen = new AtomicLong();
        HomeZones zones = new PlayerHomeZones((ids, d) -> {
            seen.set(d.remainingMillis());
            return Map.of();
        }, Duration.ofMillis(1500));
        zones.homeZones(List.of(1L), Deadline.after(3500));
        assertThat(seen.get()).isBetween(1000L, 1500L);
        zones.homeZones(List.of(1L), Deadline.after(300));
        assertThat(seen.get()).isLessThanOrEqualTo(300L);
        zones.homeZones(List.of(1L), Deadline.after(0));
        assertThat(seen.get()).as("请求预算已用完：交给严格读按「超过请求预算」抛出").isZero();
    }

    @Test
    void 缺省单次超时是1500毫秒() {
        assertThat(PlayerHomeZones.DEFAULT_TIMEOUT).isEqualTo(Duration.ofMillis(1500));
        AtomicLong seen = new AtomicLong();
        new PlayerHomeZones((ids, d) -> {
            seen.set(d.remainingMillis());
            return Map.of();
        }).homeZones(List.of(1L), Deadline.after(60_000));
        assertThat(seen.get()).isBetween(1000L, 1500L);
    }

    @Test
    void 单次超时必须为正() {
        assertThatThrownBy(() -> new PlayerHomeZones((ids, d) -> Map.of(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlayerHomeZones((ids, d) -> Map.of(), Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 单人查询_缺项与0都按0_无符号号与区原样() {
        List<List<Long>> asked = new ArrayList<>();
        HomeZones zones = new PlayerHomeZones((ids, d) -> {
            asked.add(ids);
            return Map.of(BIG, profile(BIG, -1), 2L, profile(2, 0));
        });
        assertThat(zones.homeZoneOf(BIG, Deadline.after(3500))).as("zone_id 是 uint32 位模式").isEqualTo(-1);
        assertThat(zones.homeZoneOf(2L, Deadline.after(3500))).as("zone_id = 0 → 未知").isZero();
        assertThat(zones.homeZoneOf(3L, Deadline.after(3500))).as("没有行 → 未知").isZero();
        assertThat(asked).containsExactly(List.of(BIG), List.of(2L), List.of(3L));
        HomeZones nullMap = (ids, d) -> null;
        assertThat(nullMap.homeZoneOf(1L, Deadline.after(3500))).as("实现返回 null 也按未知").isZero();
    }

    @Test void CURRENT查询稳定zero与缺行只省略_混合有效home保留uint32并按原锁序重核() throws Exception {
        var before=new java.util.HashMap<Long,Long>();before.put(1L,3L);before.put(2L,0L);before.put(BIG,0xffff_ffffL);
        var db=new HomeSql(before,before);
        Map<Long,Integer> got=PlayerRouteAuthority.readCurrentHomes(db.connection,List.of(BIG,3L,2L,1L),2);
        assertThat(got).containsExactlyInAnyOrderEntriesOf(Map.of(1L,3,BIG,-1));
        assertThat(db.events).containsExactly("CURRENT","META","PRE:1","PRE:2","PRE:3","PRE:"+Long.toUnsignedString(BIG),
                "ZONE:3","ZONE:4294967295","P:1","P:2","P:3","P:"+Long.toUnsignedString(BIG));
        assertThat(db.timeouts).allSatisfy(seconds->assertThat(seconds).isBetween(1,2));
        org.mockito.Mockito.verify(db.fence).requireNormalTransaction();
        assertThatThrownBy(()->got.put(4L,4)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void authoritySource生产构造将zero和缺行交给业务缺项_同事务提交恢复状态() throws Exception {
        var before=Map.of(1L,3L,2L,0L);var db=new HomeSql(before,before);db.auto.set(true);
        var source=org.mockito.Mockito.mock(javax.sql.DataSource.class);
        org.mockito.Mockito.when(source.getConnection()).thenReturn(db.connection);
        assertThat(new PlayerHomeZones(source).homeZones(List.of(1L,2L,3L),Deadline.after(10_000)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(1L,3));
        org.mockito.Mockito.verify(db.connection).commit();
        org.mockito.Mockito.verify(db.connection,org.mockito.Mockito.never()).rollback();
        assertThat(db.auto.get()).isTrue();org.mockito.Mockito.verify(db.connection).close();
    }

    @Test void CURRENT查询缺项也不能跳过冻结meta或坏zone围栏() throws Exception {
        var zero=new HomeSql(Map.of(1L,0L),Map.of(1L,0L));zero.writable=false;
        assertThatThrownBy(()->PlayerRouteAuthority.readCurrentHomes(zero.connection,List.of(1L),2)).isInstanceOf(java.sql.SQLException.class);
        assertThat(zero.events).containsExactly("CURRENT","META");
        var live=new HomeSql(Map.of(1L,3L),Map.of(1L,3L));live.fenced=true;
        assertThatThrownBy(()->PlayerRouteAuthority.readCurrentHomes(live.connection,List.of(1L),2)).isInstanceOf(java.sql.SQLException.class);
        assertThat(live.events).doesNotContain("P:1");
    }

    @Test void CURRENT查询SQLNULL负值与越uint32仍拒绝_零号与无预算也拒绝() throws Exception {
        for(Long corrupt:java.util.Arrays.asList(null,-1L,0x1_0000_0000L)){
            var rows=new java.util.HashMap<Long,Long>();rows.put(1L,corrupt);var db=new HomeSql(rows,rows);
            assertThatThrownBy(()->PlayerRouteAuthority.readCurrentHomes(db.connection,List.of(1L),2)).isInstanceOf(java.sql.SQLException.class);
        }
        var db=new HomeSql(Map.of(),Map.of());
        assertThatThrownBy(()->PlayerRouteAuthority.readCurrentHomes(db.connection,List.of(0L),2)).isInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(()->PlayerRouteAuthority.readCurrentHomes(db.connection,List.of(1L),0)).isInstanceOf(java.sql.SQLException.class);
        org.mockito.Mockito.verifyNoInteractions(db.fence);
    }

    @Test void CURRENT查询预读后home变化一律重启_不返回新未锁home() throws Exception {
        for(var before:List.of(Map.of(1L,0L),Map.<Long,Long>of(),Map.of(1L,7L))){
            var db=new HomeSql(before,Map.of(1L,3L));
            var error=org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                    ()->PlayerRouteAuthority.readCurrentHomes(db.connection,List.of(1L),2));
            assertThat(error.getSQLState()).isEqualTo("40001");assertThat(error.getErrorCode()).isEqualTo(9007);
        }
    }

    @Test void CURRENT查询门禁拒绝先于任何SQL_写lock含extraZones仍严格拒绝zero() throws Exception {
        var stopped=new HomeSql(Map.of(1L,0L),Map.of(1L,0L));
        org.mockito.Mockito.doThrow(new java.sql.SQLException("boot退役","40001")).when(stopped.fence).requireNormalTransaction();
        assertThatThrownBy(()->PlayerRouteAuthority.readCurrentHomes(stopped.connection,List.of(1L),2)).isInstanceOf(java.sql.SQLException.class);
        assertThat(stopped.events).isEmpty();
        org.mockito.Mockito.verify(stopped.connection,org.mockito.Mockito.never()).prepareStatement(org.mockito.ArgumentMatchers.anyString());
        for(boolean extra:new boolean[]{false,true}){
            var db=new HomeSql(Map.of(1L,0L),Map.of(1L,0L));
            assertThatThrownBy(()->{if(extra)PlayerRouteAuthority.lock(db.connection,List.of(1L),List.of(3),2);
                else PlayerRouteAuthority.lock(db.connection,List.of(1L),2);}).isInstanceOf(java.sql.SQLException.class).hasMessageContaining("归属区损坏");
        }
    }

    /** 只提供确定的JDBC原件/重读结果；真实CURRENT入口仍调用Connection.unwrap(Fence)。 */
    private static final class HomeSql {
        final java.sql.Connection connection=org.mockito.Mockito.mock(java.sql.Connection.class);
        final com.game.common.sql.AuthorityConnection.Fence fence=org.mockito.Mockito.mock(com.game.common.sql.AuthorityConnection.Fence.class);
        final java.util.concurrent.atomic.AtomicBoolean auto=new java.util.concurrent.atomic.AtomicBoolean(false);
        final List<String> events=new ArrayList<>();final List<Integer> timeouts=new ArrayList<>();
        final Map<Long,Long> before,current;boolean writable=true,fenced;
        HomeSql(Map<Long,Long> before,Map<Long,Long> current)throws Exception{
            this.before=new java.util.HashMap<>(before);this.current=new java.util.HashMap<>(current);
            org.mockito.Mockito.when(connection.getAutoCommit()).thenAnswer(call->auto.get());
            org.mockito.Mockito.doAnswer(call->{auto.set(call.getArgument(0));return null;}).when(connection).setAutoCommit(org.mockito.ArgumentMatchers.anyBoolean());
            org.mockito.Mockito.when(connection.unwrap(com.game.common.sql.AuthorityConnection.Fence.class)).thenReturn(fence);
            org.mockito.Mockito.doAnswer(call->{events.add("CURRENT");return null;}).when(fence).requireNormalTransaction();
            var metadata=org.mockito.Mockito.mock(java.sql.DatabaseMetaData.class);
            org.mockito.Mockito.when(connection.getMetaData()).thenReturn(metadata);
            org.mockito.Mockito.when(metadata.getDatabaseProductName()).thenReturn("H2");
            org.mockito.Mockito.when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call->statement(call.getArgument(0)));
        }
        private java.sql.PreparedStatement statement(String sql)throws Exception{
            var ps=org.mockito.Mockito.mock(java.sql.PreparedStatement.class);Object[] parameter={null};
            org.mockito.Mockito.doAnswer(call->{parameter[0]=call.getArgument(1);return null;}).when(ps).setObject(org.mockito.ArgumentMatchers.eq(1),org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.doAnswer(call->{parameter[0]=call.getArgument(1);return null;}).when(ps).setLong(org.mockito.ArgumentMatchers.eq(1),org.mockito.ArgumentMatchers.anyLong());
            org.mockito.Mockito.doAnswer(call->{timeouts.add(call.getArgument(0));return null;}).when(ps).setQueryTimeout(org.mockito.ArgumentMatchers.anyInt());
            org.mockito.Mockito.when(ps.executeQuery()).thenAnswer(call->{
                var rows=org.mockito.Mockito.mock(java.sql.ResultSet.class);
                if(sql.equals("SELECT generation,writable,fence_shards FROM route_meta WHERE id=1")){
                    events.add("META");org.mockito.Mockito.when(rows.next()).thenReturn(true,false);
                    org.mockito.Mockito.when(rows.getString(1)).thenReturn("e0bded15-6e55-438c-b067-55ac20decc4e");
                    org.mockito.Mockito.when(rows.getString(2)).thenReturn(writable?"1":"0");
                    org.mockito.Mockito.when(rows.getInt(3)).thenReturn(PlayerRouteAuthority.FENCE_SHARDS);
                }else if(sql.startsWith("SELECT zone_id FROM player WHERE player_id=?")){
                    long id=((Number)parameter[0]).longValue();boolean locked=sql.endsWith(" FOR UPDATE");var values=locked?current:before;
                    events.add((locked?"P:":"PRE:")+Long.toUnsignedString(id));
                    org.mockito.Mockito.when(rows.next()).thenReturn(values.containsKey(id),false);
                    Long value=values.get(id);org.mockito.Mockito.when(rows.getLong(1)).thenReturn(value==null?0L:value);
                    org.mockito.Mockito.when(rows.wasNull()).thenReturn(values.containsKey(id)&&value==null);
                }else if(sql.equals("SELECT zone_id FROM zone_merged_into WHERE zone_id=?")){
                    org.mockito.Mockito.when(rows.next()).thenReturn(false);
                }else if(sql.startsWith("SELECT shard,run_id,revision FROM zone_merge_fence WHERE zone_id=? ORDER BY shard")){
                    events.add("ZONE:"+parameter[0]);int[] shard={-1};
                    org.mockito.Mockito.when(rows.next()).thenAnswer(next->++shard[0]<PlayerRouteAuthority.FENCE_SHARDS);
                    org.mockito.Mockito.when(rows.getInt(1)).thenAnswer(get->shard[0]);
                    org.mockito.Mockito.when(rows.getString(2)).thenReturn(fenced?"active-run":"");org.mockito.Mockito.when(rows.getLong(3)).thenReturn(1L);
                }else throw new AssertionError("未声明的查询："+sql);
                return rows;
            });return ps;
        }
    }
}
