package com.game.scene.storage;

import static org.junit.jupiter.api.Assertions.*;
import com.alibaba.druid.pool.DruidDataSource;
import com.game.common.sql.testing.AuthoritySqlFixture;
import com.game.discovery.sql.AuthorityDataSourcePolicy;
import com.game.player.store.*;
import com.game.player.store.state.PlayerState;
import com.game.scene.world.PlayerSave;
import com.game.scene.world.Vec3;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实Mapper、两笔独立事务与受限normal主体；本测试独占夹具和在线玩家。 */
class SceneMaintenanceDrainTiDbTest {
    @Test void tx1AcceptedDoesNotAuthorizeStoppingFinalRecoveryBeforeTx2OwnerRelease()throws Exception{
        String root=System.getProperty("game.it.authority.jdbc",System.getProperty("game.it.mysql"));
        if(root==null||root.isBlank())throw new IllegalStateException("自有Scene夹具缺少game.it.authority.jdbc（兼容game.it.mysql）根URL");
        long player=1;
        String source=UUID.randomUUID().toString();
        var options=AuthoritySqlFixture.Options.fromEnvironment(root,
                System.getProperty("game.it.mysql.user","root"),
                System.getenv().getOrDefault("GAME_MYSQL_PASSWORD",""),"jscenedrain",false,List.of(1));
        // prepare在schema回调期间维持UNKNOWN；种子提交未明时保留本实例原件，不由close误清理。
        try(var fixture=AuthoritySqlFixture.prepareWithPlayerSchema(options,(connection,principal)->{
                seedOnlinePlayer(connection,player,source);
            });var pool=new DruidDataSource()){
            pool.setUrl(fixture.jdbcUrl("?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=10000"));
            pool.setUsername(fixture.normalUser());pool.setPassword(fixture.normalPassword());pool.setMaxActive(4);pool.setMaxWait(3000);
            AuthorityDataSourcePolicy.protectDruid(pool,fixture.writerBoot(),true);
            var store=new PlayerStore(playerMapper(pool),System::currentTimeMillis,new DataSourceTransactionManager(pool));
            var row=store.findPlayer(player).orElseThrow();var state=store.loadState(player);
            var recovery=new FinalSaveRecovery(store,Runnable::run,Runnable::run,source,System::currentTimeMillis);
            recovery.submit(new PlayerSave(player,row.getOwnerEpoch(),Math.toIntExact(row.getLevel()),row.getSceneConfigId(),new Vec3(row.getPosX(),row.getPosY(),row.getPosZ()),state),ignored->{});
            assertEquals(0,recovery.awaitRegistered(0));assertTrue(recovery.awaitApplied(0)>0);assertFalse(recovery.stopIfApplied());
            assertEquals(PlayerStore.FinalSaveStatus.ACCEPTED,store.probeFinalSave(player,row.getOwnerEpoch(),Duration.ofSeconds(3)).status());
            recovery.sweep();assertEquals(PlayerStore.FinalSaveStatus.APPLIED,store.probeFinalSave(player,row.getOwnerEpoch(),Duration.ofSeconds(3)).status());
            assertEquals(0,recovery.awaitApplied(0));assertTrue(recovery.stopIfApplied());
            try(var connection=pool.getConnection();var query=connection.prepareStatement("SELECT owner_released FROM player WHERE player_id=?")){query.setObject(1,new java.math.BigInteger(Long.toUnsignedString(player)));try(var rows=query.executeQuery()){assertTrue(rows.next());assertEquals(1,rows.getInt(1));}}
        }
    }

    private static PlayerMapper playerMapper(DataSource dataSource)throws Exception{
        var factory=new SqlSessionFactoryBean();factory.setDataSource(dataSource);
        var configuration=new org.apache.ibatis.session.Configuration();configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);factory.afterPropertiesSet();
        var mapper=new MapperFactoryBean<>(PlayerMapper.class);mapper.setSqlSessionFactory(factory.getObject());mapper.afterPropertiesSet();
        return mapper.getObject();
    }

    private static void seedOnlinePlayer(Connection connection,long player,String source)throws Exception{
        // 仅借用prepare持有的schema连接，关闭抑制代理不接管原连接的生命周期。
        var dataSource=new SingleConnectionDataSource(connection,true);
        var mapper=playerMapper(dataSource);
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status->{
            long now=System.currentTimeMillis();
            String account="scene_"+source.replace("-","");
            var row=new PlayerRow();row.setPlayerId(player);row.setAccount(account);row.setZoneId(1);
            row.setName("场景排空"+source);row.setClassId(1);row.setGender(1);row.setLevel(1);row.setSceneConfigId(1);
            row.setOwnerEpoch(0);row.setCreatedAt(now);row.setUpdatedAt(now);
            assertEquals(1,mapper.insertAccountIfAbsent(account,now));
            assertEquals(1,mapper.insertPlayer(row,PlayerStore.nameKey(row.getName())));
            assertEquals(1,mapper.insertInitialState(player,PlayerState.getDefaultInstance().toByteArray(),now));
            long leaseUntil=Math.addExact(now,PlayerStore.OWNER_LEASE.toMillis());
            assertEquals(1,mapper.claimOwnerEpoch(player,now,leaseUntil));
            var owner=mapper.selectOwnerForUpdate(player);assertNotNull(owner);
            assertEquals(1,owner.ownerEpoch());assertFalse(owner.released());assertEquals(leaseUntil,owner.leaseUntil());
        });
    }
}
