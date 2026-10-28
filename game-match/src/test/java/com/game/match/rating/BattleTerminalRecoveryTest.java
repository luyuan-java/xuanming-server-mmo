package com.game.match.rating;

import com.game.common.sql.testing.AuthoritySqlFixture;
import com.game.discovery.sql.AuthorityDataSourcePolicy;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.api.PlayerInboxService;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.match.completion.pb.BattleCompletionRow;
import com.game.match.completion.pb.BattlePlayerCompletionRow;
import com.game.match.global.GlobalMatchProperties;
import com.game.match.global.GlobalMatchStore;
import com.game.match.global.pb.GlobalFormationRow;
import com.game.match.global.pb.GlobalTicketRow;
import com.game.match.global.pb.GlobalProbeProfile;
import com.game.proto.*;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.match.*;
import com.game.proto.player_inbox.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 终局、全局结果和个人永久收据共用原件；内存SQL与真实TiDB的证据分别记账。 */
class BattleTerminalRecoveryTest {
    private static final String SECRET = "终局回归独立密钥只用于本机夹具0123456789abcdef";

    private final List<Memory> memoryFixtures = new ArrayList<>();
    @AfterEach void closeMemoryFixtures() throws Exception {
        Exception failure = null;
        for (Memory memory : memoryFixtures) {
            try { memory.close(); }
            catch (Exception cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
        }
        memoryFixtures.clear();
        if (failure != null) throw failure;
    }

    @Test void 完整来源递归拒绝新资产语义及个人超限_不影响旧纯归档() {
        var event = BattleCompletionStoreCases.complete(801).toBuilder().setFinishedAtMs(123).build();
        assertEquals(2, BattleCompletionStore.validateTerminal(event.toByteString()).size());
        var unknown = UnknownFieldSet.newBuilder().addField(900, UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build();
        var item = BattleItemEntry.newBuilder().setItemTableId(11).setCount(1).setUnknownFields(unknown).build();
        var nested = event.toBuilder().setSettlements(0, event.getSettlements(0).toBuilder().addItemsGained(item)).build();
        assertThrows(IllegalArgumentException.class, () -> BattleCompletionStore.validateTerminal(nested.toByteString()));
        var huge = event.getSettlements(0).toBuilder();
        for (int i = 0; i < 5000; i++) huge.addItemsGained(BattleItemEntry.newBuilder().setItemTableId(i + 1).setCount(1));
        assertThrows(IllegalArgumentException.class, () -> BattleCompletionStore.validateTerminal(event.toBuilder().setSettlements(0, huge).build().toByteString()));
        assertThrows(IllegalArgumentException.class, () -> BattleCompletionStore.validateTerminal(event.toBuilder().removeSettlements(1).build().toByteString()));
    }

    @Test void Kafka与个人两去向各自重放原字节_只有同永久seq的APPLIED才终结个人() throws Exception {
        var f = new Memory(); var inbox = new Inbox(); var published = new ArrayList<ByteString>();
        var original = f.seed(802);
        try (var replay = new BattleCompletionReplay(f.store, noDeadlines(), inbox, (event, raw) -> {
            published.add(raw); return CompletableFuture.completedFuture(null);
        }, SECRET, () -> 1_000_000)) {
            replay.round();
            assertEquals(List.of(original), published); assertEquals(2, inbox.enqueued.size());
            assertTrue(f.store.pendingResults(0).isEmpty());
            assertEquals(2, f.store.pendingPlayers(0, 0).size());
            assertTrue(f.store.pendingPlayers(0, 0).stream().allMatch(row -> row.getInboxSeq() != 0 && !row.getApplied()));
            inbox.applied = true;f.makeDue();
            replay.round(); // 当前页结束，游标回头；不依赖进程内持久游标。
            replay.round();
            assertEquals(2, inbox.lookedUp.size()); assertEquals(2, inbox.enqueued.size());
            assertTrue(f.store.pendingPlayers(0, 0).isEmpty()); assertEquals(1, published.size());
            for (int i = 0; i < 2; i++) assertEquals(clearAuth(inbox.enqueued.get(i)), clearAuth(inbox.lookedUp.get(i)));
        }
    }

    @Test void 离线或未知回复不堵后来成员_不换原owner时间正文_重启从永久seq查询() throws Exception {
        var f = new Memory(); f.seed(803); var inbox = new Inbox(); inbox.unavailable = 1;
        try (var replay = new BattleCompletionReplay(f.store, noDeadlines(), inbox, (event, raw) -> CompletableFuture.completedFuture(null), SECRET, () -> 1_000_000)) {
            replay.round();
            var rows = f.store.pendingPlayers(0, 0);
            assertEquals(0, rows.stream().filter(row -> row.getPlayerId() == 1).findFirst().orElseThrow().getInboxSeq());
            assertTrue(rows.stream().filter(row -> row.getPlayerId() == 2).findFirst().orElseThrow().getInboxSeq() > 0);
        }
        inbox.unavailable = 0; inbox.applied = true;f.makeDue();
        try (var restarted = new BattleCompletionReplay(f.store, noDeadlines(), inbox, (event, raw) -> CompletableFuture.completedFuture(null), SECRET, () -> 1_000_001)) {
            restarted.round();
            assertEquals(1, inbox.lookedUp.size());
            assertTrue(f.store.pendingPlayers(0, 0).isEmpty());
            var first = inbox.enqueued.getFirst(); var retried = inbox.enqueued.getLast();
            assertEquals(clearAuth(first), clearAuth(retried));
        }
    }

    @Test void 首投和等待APPLIED各保留八个份额_固定上界后再回头()throws Exception {
        try(var f=new Memory(true)){for(int i=0;i<10;i++)f.seed(1000+i);
        try(var c=f.schemaConnection();var s=c.createStatement()){s.executeUpdate("UPDATE match_battle_player_completion SET inbox_seq=10+player_id WHERE battle_id>=1005");}
        var inbox=new Inbox();
        try(var replay=new BattleCompletionReplay(f.store,noDeadlines(),inbox,(event,raw)->CompletableFuture.completedFuture(null),SECRET,()->1_000_000)){
            replay.round();assertEquals(8,inbox.enqueued.size());assertEquals(8,inbox.lookedUp.size());
            // 连续新首投不挤占等待APPLIED的份额，也不扩大已固定的旧轮上界。
            f.seed(1010);replay.round();assertEquals(10,inbox.enqueued.size());assertEquals(10,inbox.lookedUp.size());
            replay.round();assertEquals(10,inbox.enqueued.size());
            replay.round();assertEquals(12,inbox.enqueued.size());assertEquals(10,inbox.lookedUp.size());
            assertTrue(inbox.enqueued.stream().allMatch(r->r.getBattleId()<1005||r.getBattleId()==1010));
            assertTrue(inbox.lookedUp.stream().allMatch(r->r.getBattleId()>=1005&&r.getBattleId()<1010));
        }
        }
    }

    @Test void 同一原件投递代次仅一CAS领取_到期前零RPC_永久seq后只Lookup()throws Exception {
        try(var f=new Memory(true)){f.seed(1020);var original=f.store.pendingPlayers(0,0).getFirst();
        assertTrue(f.store.claimInboxAttempt(original));assertFalse(f.store.claimInboxAttempt(original));
        var claimed=f.store.pendingPlayers(0,0).getFirst();assertTrue(claimed.getRetryMs()>0);
        assertFalse(f.store.claimInboxAttempt(claimed));
        f.store.inboxReceipt(original,10+original.getPlayerId(),false);
        var known=f.store.pendingPlayers(0,0).getFirst();assertEquals(claimed.getRetryMs(),known.getRetryMs());
        assertEquals(original.getOriginal(),known.getOriginal());assertEquals(original.getOwnerAccount(),known.getOwnerAccount());
        assertFalse(f.store.claimInboxAttempt(original));
        try(var c=f.schemaConnection();var s=c.createStatement()){s.executeUpdate("UPDATE match_battle_player_completion SET applied=1 WHERE player_id=2");}
        var inbox=new Inbox();inbox.applied=true;
        try(var replay=new BattleCompletionReplay(f.store,noDeadlines(),inbox,(event,raw)->CompletableFuture.completedFuture(null),SECRET,()->1_000_000)){
            replay.round();assertTrue(inbox.enqueued.isEmpty());assertTrue(inbox.lookedUp.isEmpty());
            f.makeDue();replay.round();assertTrue(inbox.enqueued.isEmpty());assertEquals(1,inbox.lookedUp.size());
            assertEquals(known.getInboxSeq(),10+inbox.lookedUp.getFirst().getPlayerId());assertTrue(f.store.pendingPlayers(0,0).isEmpty());
        }
        }
    }
    @Test void Inbox回复必须绑定方法原全文永久seq及内容_APPLIED不能由布尔摘要代替() throws Exception {
        var f = new Memory(); f.seed(804); var inbox = new Inbox();
        var request = BattleSettlementInboxRequest.newBuilder().setPlayerId(1).setBattleId(804).setExpectedOwnerAccount("account-1")
                .setExpectedOwnerRevision(1).setIssuedAtMs(123).setSettlementPayload(BattleCompletionStoreCases.complete(804).getSettlements(0).toByteString()).build();
        try (var replay = new BattleCompletionReplay(f.store, noDeadlines(), inbox, (event, raw) -> CompletableFuture.completedFuture(null), SECRET, () -> 1_000_000)) {
            var valid = inbox.receipt(PlayerInboxService.BATTLE_ENQUEUE_METHOD, request);
            assertDoesNotThrow(() -> replay.verifyReceipt(PlayerInboxService.BATTLE_ENQUEUE_METHOD, request, valid, valid.getSeq()));
            assertThrows(SecurityException.class, () -> replay.verifyReceipt(PlayerInboxService.BATTLE_LOOKUP_METHOD, request, valid, valid.getSeq()));
            assertThrows(SecurityException.class, () -> replay.verifyReceipt(PlayerInboxService.BATTLE_ENQUEUE_METHOD, request, valid, valid.getSeq() + 1));
            var unknown = valid.toBuilder().clearAuth().setState(PlayerInboxItemState.PLAYER_INBOX_ITEM_UNSPECIFIED).build();
            var signed = ServiceCallSignatures.sign(PlayerInboxService.BATTLE_ENQUEUE_METHOD + "/reply", unknown, "inbox", SECRET, 1_000_000);
            assertThrows(SecurityException.class, () -> replay.verifyReceipt(PlayerInboxService.BATTLE_ENQUEUE_METHOD, request, signed, 0));
            assertThrows(SecurityException.class, () -> replay.verifyReceipt(PlayerInboxService.BATTLE_ENQUEUE_METHOD, request.toBuilder().setIssuedAtMs(124).build(), valid, 0));
        }
    }

    @Test void 无Create原件的开房前取消保旧安全合同_已有Create缺ABORT不能签state5() {
        String method=com.game.match.activity.MatchInternalServiceImpl.CANCEL_METHOD;
        var request=ServiceCallSignatures.sign(method,CancelActivityBattleRequest.newBuilder()
                .setOriginal(StartActivityBattleRequest.newBuilder().setRequestId(1)).build(),"guild",SECRET,System.currentTimeMillis());
        for(int shape=0;shape<3;shape++) {
            var service=org.mockito.Mockito.mock(com.game.match.activity.ActivityBattleService.class);
            var store=org.mockito.Mockito.mock(GlobalMatchStore.class);
            var formation=GlobalFormationRow.newBuilder().setBattleId(905).setState(5);
            if(shape>0)formation.setCreateRequest(CreateBattleRequest.newBuilder().setBattleId(905).build().toByteString());
            org.mockito.Mockito.when(store.formation(905)).thenReturn(formation.build());
            org.mockito.Mockito.when(store.resolveTerminalAfterDeadline(905)).thenReturn(shape==2?2:0);
            org.mockito.Mockito.when(service.cancel(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                    .thenReturn(CancelActivityBattleResponse.newBuilder().setBattleId(905).setFormationState(5)
                            .setRequestDigest(ServiceCallSignatures.digest(request)).build());
            var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory();beans.addBean("global",store);
            var env=new org.springframework.mock.env.MockEnvironment().withProperty("MMORPG_MATCH_INTERNAL_SECRET_GUILD",SECRET);
            var provider=new com.game.match.activity.MatchInternalServiceImpl(service,Runnable::run,
                    new com.game.match.MatchProperties(null,null,null,null,null,null,null,null,null,null,null,null,null),
                    beans.getBeanProvider(GlobalMatchStore.class),env);
            if(shape==1)assertThrows(CompletionException.class,()->provider.cancelActivityBattle(request).join());
            else {
                var response=provider.cancelActivityBattle(request).join();assertEquals(5,response.getFormationState());
                assertEquals(shape==2?2:0,response.getTerminalDecisionValue());
            }
            if(shape==0)org.mockito.Mockito.verify(store,org.mockito.Mockito.never()).resolveTerminalAfterDeadline(905);
        }
    }

    @Test void 正式截止轮询有界推进游标_旧协议和失败首项不阻塞后页() throws Exception {
        var f=new Memory();var formations=noDeadlines();
        var first=java.util.stream.LongStream.rangeClosed(1,16).boxed().toList();
        org.mockito.Mockito.when(formations.terminalDeadlineCandidates(0,0)).thenReturn(new GlobalMatchStore.TerminalDeadlineBatch(17,first));
        org.mockito.Mockito.when(formations.terminalDeadlineCandidates(16,17)).thenReturn(new GlobalMatchStore.TerminalDeadlineBatch(17,List.of(17L)));
        org.mockito.Mockito.when(formations.resolveTerminalAfterDeadline(1)).thenThrow(new SecurityException("真实adopted0仍未知"));
        org.mockito.Mockito.when(formations.resolveTerminalAfterDeadline(2)).thenReturn(0);
        org.mockito.Mockito.when(formations.resolveTerminalAfterDeadline(17)).thenReturn(2);
        try(var replay=new BattleCompletionReplay(f.store,formations,new Inbox(),(event,raw)->CompletableFuture.completedFuture(null),SECRET,()->1_000_000)) {
            replay.round();
            org.mockito.Mockito.verify(formations,org.mockito.Mockito.never()).resolveTerminalAfterDeadline(17);
            replay.round();
            org.mockito.Mockito.verify(formations).resolveTerminalAfterDeadline(17);
            replay.round();replay.round();
            org.mockito.Mockito.verify(formations,org.mockito.Mockito.times(2)).resolveTerminalAfterDeadline(1);
        }
    }

    private static GlobalMatchStore noDeadlines() {
        var store=org.mockito.Mockito.mock(GlobalMatchStore.class);
        org.mockito.Mockito.when(store.terminalDeadlineCandidates(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong())).thenReturn(new GlobalMatchStore.TerminalDeadlineBatch(0,List.of()));
        return store;
    }

    private static BattleSettlementInboxRequest clearAuth(BattleSettlementInboxRequest value) { return value.toBuilder().clearAuth().build(); }
    private static final class Inbox implements PlayerInboxService {
        final List<BattleSettlementInboxRequest> enqueued = new ArrayList<>(), lookedUp = new ArrayList<>();
        long unavailable; boolean applied;
        public CompletableFuture<BattleSettlementInboxResponse> enqueueBattleSettlement(BattleSettlementInboxRequest request) {
            enqueued.add(request); return answer(BATTLE_ENQUEUE_METHOD, request);
        }
        public CompletableFuture<BattleSettlementInboxResponse> lookupBattleSettlement(BattleSettlementInboxRequest request) {
            lookedUp.add(request); return answer(BATTLE_LOOKUP_METHOD, request);
        }
        private CompletableFuture<BattleSettlementInboxResponse> answer(String method, BattleSettlementInboxRequest request) {
            if (unavailable == request.getPlayerId()) return CompletableFuture.failedFuture(new IllegalStateException("固定成员离线"));
            return CompletableFuture.completedFuture(receipt(method, request));
        }
        BattleSettlementInboxResponse receipt(String method, BattleSettlementInboxRequest request) {
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                digest.update(ByteBuffer.allocate(12).putInt(PlayerInboxMessageType.PLAYER_INBOX_MSG_BATTLE_SETTLEMENT_VALUE).putLong(request.getIssuedAtMs()).array());
                var response = BattleSettlementInboxResponse.newBuilder().setPlayerId(request.getPlayerId()).setBattleId(request.getBattleId())
                        .setSeq(10 + request.getPlayerId()).setContentSha256(ByteString.copyFrom(digest.digest(request.getSettlementPayload().toByteArray())))
                        .setRequestDigest(ServiceCallSignatures.digest(request)).setState(applied ? PlayerInboxItemState.PLAYER_INBOX_ITEM_APPLIED
                                : PlayerInboxItemState.PLAYER_INBOX_ITEM_PENDING).build();
                return ServiceCallSignatures.sign(method + "/reply", response, "inbox", SECRET, 1_000_000);
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
        public CompletableFuture<FetchPlayerInboxResponse> fetchItems(FetchPlayerInboxRequest request) { return CompletableFuture.failedFuture(new AssertionError("不使用Scene入口")); }
        public CompletableFuture<AckPlayerInboxResponse> ackItems(AckPlayerInboxRequest request) { return CompletableFuture.failedFuture(new AssertionError("不冒SceneACK")); }
    }
    private final class Memory implements AutoCloseable {
        final DataSource data;
        final java.util.concurrent.atomic.AtomicLong databaseMillis = new java.util.concurrent.atomic.AtomicLong(1_000_000);
        final BattleCompletionStore store;
        private RealTiDb ownedTiDb;
        Memory() throws Exception { this(false); }
        Memory(boolean allowRealTiDb) throws Exception {
            String required=System.getProperty("match.terminal.retry.it.require","");
            if(allowRealTiDb&&!required.isEmpty()&&!required.equals("1"))
                throw new IllegalArgumentException("A3真库require只允许显式1");
            try {
                if(allowRealTiDb&&required.equals("1")) {
                    String endpoint=System.getenv("MATCH_IT_TIDB");
                    if(endpoint==null||endpoint.isBlank())throw new IllegalStateException("A3真库require=1但缺MATCH_IT_TIDB，不得降级H2");
                    // 复用本文件正式TiDB夹具：随机自有schema、强会话、生产DDL和原清理合同。
                    ownedTiDb=new RealTiDb();ownedTiDb.open();data=ownedTiDb.data;
                    BattleCompletionStore.sync(data);
                } else {
                    data=RatingTestDatabase.h2DataSource("terminal-" + UUID.randomUUID());
                    try (var c = RatingTestDatabase.h2SchemaDataSource(data).getConnection(); var sql = c.createStatement()) {
                        for (var table : BattleCompletionStore.registry().tables()) sql.execute(RatingTestDatabase.h2Ddl(table.createTableSql()));
                    }
                    RatingTestDatabase.initializeRouteAuthority(data);
                    try (var c = RatingTestDatabase.h2SchemaDataSource(data).getConnection(); var sql = c.createStatement()) {
                        sql.execute("ALTER TABLE player ADD account VARCHAR(255) NOT NULL DEFAULT ''");
                        sql.execute("ALTER TABLE player ADD owner_revision BIGINT NOT NULL DEFAULT 1");
                        sql.execute("ALTER TABLE player ADD trade_listing_id BIGINT NOT NULL DEFAULT 0");
                    }
                }
                store=new BattleCompletionStore(data,ignored->databaseMillis.get());
                try (var c = schemaConnection(); var sql = c.createStatement()) {
                    sql.execute("INSERT INTO player(player_id,account,owner_revision) VALUES(1,'account-1',1),(2,'account-2',1)");
                }
                if (ownedTiDb == null) memoryFixtures.add(this);
            } catch(Exception|AssertionError failure) {
                try { close(); } catch(Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        @Override public void close()throws Exception { if(ownedTiDb!=null)ownedTiDb.close(); else if(data instanceof AutoCloseable memory)memory.close(); }
        void makeDue()throws Exception {
            try(var c=schemaConnection();var s=c.createStatement()){s.executeUpdate("UPDATE match_battle_player_completion SET retry_ms=0 WHERE applied=0");}
        }
        java.sql.Connection schemaConnection()throws java.sql.SQLException{return ownedTiDb==null?RatingTestDatabase.h2SchemaDataSource(data).getConnection():ownedTiDb.fixture.schemaConnection();}
        ByteString seed(long battle) throws Exception {
            var event = BattleCompletionStoreCases.complete(battle).toBuilder().setFinishedAtMs(123).build();
            var db = BattleCompletionStore.registry();
            try (var c = schemaConnection()) {
                db.insert(c, BattleCompletionRow.newBuilder().setBattleId(battle).setFormatVersion(1).setTerminalDecision(1).setOutcomeProtocolVersion(1)
                        .setSourceKind(2).setOriginal(event.toByteString()).setOriginalDigest(BattleCompletionStore.digest(event.toByteString())).setPlayerCount(2).build());
                for (var player : event.getSettlementsList()) db.insert(c, BattlePlayerCompletionRow.newBuilder().setBattleId(battle).setPlayerId(player.getPlayerId())
                        .setOriginal(player.toByteString()).setOriginalDigest(BattleCompletionStore.digest(player.toByteString()))
                        .setOwnerAccount("account-" + player.getPlayerId()).setOwnerRevision(1).setIssuedAtMs(123).build());
            }
            return event.toByteString();
        }
    }

    @Nested @EnabledIfEnvironmentVariable(named = "MATCH_IT_TIDB", matches = ".+")
    class RealTiDb {
        DruidDataSource data; String root; AuthoritySqlFixture fixture; GlobalMatchStore store;
        @BeforeEach void open() throws Exception {
            root = RatingTestDatabase.tidbRoot(System.getenv("MATCH_IT_TIDB"));
            fixture=AuthoritySqlFixture.prepare(AuthoritySqlFixture.Options.fromEnvironment(root,RatingTestDatabase.MYSQL_USER,
                    RatingTestDatabase.MYSQL_PASSWORD,"jterminal",false,List.of(1,2)),(c,principal)->{
                GlobalMatchStore.registry().initializeFresh(c,principal);
                BattleCompletionStore.registry().initializeFresh(c,principal);
                try(var s=c.createStatement()) {
                s.execute("CREATE TABLE player(player_id BIGINT UNSIGNED PRIMARY KEY,account VARCHAR(255) NOT NULL,owner_revision BIGINT UNSIGNED NOT NULL,trade_listing_id BIGINT UNSIGNED NOT NULL DEFAULT 0,zone_id INT UNSIGNED NOT NULL DEFAULT 1)");
                s.execute("CREATE TABLE team_launch(request_id VARCHAR(36) PRIMARY KEY,state INT UNSIGNED NOT NULL,active BOOLEAN NOT NULL DEFAULT TRUE,team_id BIGINT UNSIGNED NOT NULL DEFAULT 0,team_version BIGINT UNSIGNED NOT NULL DEFAULT 0,lock_token VARCHAR(36) NOT NULL DEFAULT '',actor_player_id BIGINT UNSIGNED NOT NULL DEFAULT 0,owner_account VARCHAR(255) NOT NULL DEFAULT '',owner_revision BIGINT UNSIGNED NOT NULL DEFAULT 0,request_proto MEDIUMBLOB,record_proto MEDIUMBLOB,battle_id BIGINT UNSIGNED NOT NULL DEFAULT 0,updated_ms BIGINT UNSIGNED NOT NULL DEFAULT 0,original_proto MEDIUMBLOB)");
                s.execute("CREATE TABLE team_authority(team_id BIGINT UNSIGNED PRIMARY KEY,version BIGINT UNSIGNED NOT NULL,record_proto MEDIUMBLOB,tombstone TINYINT NOT NULL DEFAULT 0,active_launch_id VARCHAR(36) NOT NULL)");
                s.execute("CREATE TABLE team_policy(id INT UNSIGNED PRIMARY KEY,contract MEDIUMBLOB,allow_cross_zone TINYINT NOT NULL DEFAULT 0,contract_digest MEDIUMBLOB)");
                s.execute("CREATE TABLE team_launch_role(player_id BIGINT UNSIGNED NOT NULL,request_id VARCHAR(36) NOT NULL,state INT UNSIGNED NOT NULL,owner_account VARCHAR(255) NOT NULL DEFAULT '',owner_revision BIGINT UNSIGNED NOT NULL DEFAULT 0,PRIMARY KEY(player_id,request_id),INDEX by_request(request_id,player_id),INDEX by_state(player_id,state,request_id))");
                s.execute("CREATE TABLE guild_trial_launch(request_id BIGINT UNSIGNED PRIMARY KEY,battle_id BIGINT UNSIGNED NOT NULL DEFAULT 0,state INT UNSIGNED NOT NULL,original_proto MEDIUMBLOB,request_digest MEDIUMBLOB,created_ms BIGINT UNSIGNED NOT NULL DEFAULT 0)");
                s.execute("CREATE TABLE guild_trial_launch_role(player_id BIGINT UNSIGNED NOT NULL,request_id BIGINT UNSIGNED NOT NULL,account VARCHAR(255) NOT NULL,owner_revision BIGINT UNSIGNED NOT NULL,state INT UNSIGNED NOT NULL,PRIMARY KEY(player_id,request_id),INDEX by_request(request_id,player_id),INDEX by_state(player_id,state,request_id))");
                s.execute("CREATE TABLE guild_activity_slot(player_id BIGINT UNSIGNED PRIMARY KEY,lobby_id BIGINT UNSIGNED,expires_ms BIGINT UNSIGNED)");
                s.execute("CREATE TABLE guild_trial_lobby(lobby_id BIGINT UNSIGNED PRIMARY KEY,state INT,view_proto MEDIUMBLOB,guild_id BIGINT UNSIGNED,activity_id INT UNSIGNED)");
                }
                c.setAutoCommit(false);com.game.common.team.TeamPolicyAuthority.ensure(c,false,3);c.commit();c.setAutoCommit(true);
            });
            data = new DruidDataSource();data.setUrl(fixture.jdbcUrl("?useSSL=false&socketTimeout=4000&connectTimeout=3000"));
            data.setUsername(fixture.normalUser());data.setPassword(fixture.normalPassword());data.setMaxActive(8);data.setMaxWait(3000);
            AuthorityDataSourcePolicy.protectDruid(data,fixture.writerBoot(),true);
            store = new GlobalMatchStore(data, new GlobalMatchProperties(true, true, 30, 180, 120, SECRET));
        }
        @AfterEach void close() throws Exception {
            if (data != null) data.close();
            if(fixture!=null)fixture.close();
        }
        private GlobalTicketRow join(long player) throws Exception { return join(player,3,2); }
        private GlobalTicketRow join(long player,int mode,int size) throws Exception {
            sql("INSERT INTO player(player_id,account,owner_revision) VALUES(?,?,1)", player, "account-" + player);
            long now = store.now(); var profile = GlobalProbeProfile.newBuilder().setPlanId("p" + player).setSessionBinding("s").setOwnerAccount("account-" + player).setOwnerRevision(1)
                    .addReceiptPayloads(NetworkProbeReceipt.newBuilder().setGrant(NetworkProbeGrant.newBuilder().setPlanId("p" + player).setPlayerId(player)
                            .setSessionBinding("s").setPhysicalRegion(1).setExpiresAtMs(now + 120_000)).setSampleCount(3).setRttMs(30).setMeasuredAtMs(now).setExpiresAtMs(now + 120_000).build().toByteString()).build();
            store.plan(player, profile, now + 120_000); return store.join(player, UUID.randomUUID().toString(), profile.getPlanId(), "s", mode, 1, size, 1, 150_000);
        }
        private GlobalFormationRow prepare(long battle) throws Exception { return prepare(battle,1,3,2); }
        private GlobalFormationRow prepare(long battle,long firstPlayer,int mode,int count) throws Exception {
            List<GlobalTicketRow> members=new ArrayList<>();
            for(int i=0;i<count;i++)members.add(join(firstPlayer+i,mode,count));
            var shape = GlobalFormationRow.newBuilder().setBattleId(battle).setOperationId(UUID.randomUUID().toString()).setRegion(1).setNodeId(1)
                    .setInstanceId("original-instance").setHost("127.0.0.1").setPort(21200).setMode(mode).setConfigId(1).build();
            store.claim(members, shape, false); var f = store.acquireWork(battle);
            var request = CreateBattleRequest.newBuilder().setBattleId(battle).setPlacementOperationId(f.getOperationId()).setTargetInstanceId(f.getInstanceId())
                    .setBattleConfigId(f.getConfigId()).setMatchMode(f.getMode()).setCreatedAtMs(f.getCreatedMs()).setRequiredPhysicalRegion(f.getRegion())
                    .setDeadlineMs(f.getDeadlineMs()).setOutcomeProtocolVersion(1);
            for(int i=0;i<count;i++)request.addPlayers(BattlePlayerSnapshot.newBuilder().setPlayerId(firstPlayer+i).setTeamIndex(mode==3?i:0));
            return store.saveIntent(f, request.build().toByteArray(), QueryBattleCreationResponse.newBuilder().setState(BattleCreationState.BATTLE_CREATION_ABSENT)
                    .setTargetInstanceId(f.getInstanceId()).setSupportedOutcomeProtocolVersion(1).build());
        }
        private GlobalFormationRow expire(GlobalFormationRow f) throws Exception {
            var expired=f.toBuilder().setCreatedMs(1).setDeadlineMs(2).setCreateRequest(CreateBattleRequest.parseFrom(f.getCreateRequest()).toBuilder()
                    .setCreatedAtMs(1).setDeadlineMs(2).build().toByteString()).build();
            var registry=new com.game.pbmysql.PbMysql();registry.register(GlobalFormationRow.getDefaultInstance());
            try(var connection=fixture.schemaConnection()){registry.updateByPk(connection,expired);}
            return expired;
        }
        private CommitBattleOutcomeRequest outcome(GlobalFormationRow f) {
            return CommitBattleOutcomeRequest.newBuilder().setBattleId(f.getBattleId()).setPlacementOperationId(f.getOperationId()).setTargetInstanceId(f.getInstanceId())
                    .setRawResultPayload(BattleCompletionStoreCases.complete(f.getBattleId()).toBuilder().setBattleConfigId(1).setFinishedAtMs(store.now()).build().toByteString()).build();
        }
        private void sql(String text, Object... values) throws Exception {
            try (var c = fixture.schemaConnection(); var q = c.prepareStatement(text)) { for (int i = 0; i < values.length; i++) q.setObject(i + 1, values[i]); q.setQueryTimeout(3); q.executeUpdate(); }
        }
        @Test void 早COMPLETE后迟finish幂等_入箱PENDING不释放_实际APPLIED后才可下局() throws Exception {
            var f = prepare(901); var request = outcome(f); var terminal = store.commitOutcome(request);
            assertEquals(1, terminal.getTerminalDecision()); store.finish(f, true);
            assertThrows(RuntimeException.class, () -> store.finish(f, false));
            var ticket = store.ticket(1);
            assertThrows(RuntimeException.class, () -> store.releaseReady(1, ticket.getTicket(), 901));
            var completion = new BattleCompletionStore(data); var personal = completion.pendingPlayers(0, 0).getFirst();
            completion.inboxReceipt(personal, 77, false);
            assertThrows(RuntimeException.class, () -> store.releaseReady(1, ticket.getTicket(), 901));
            completion.inboxReceipt(personal, 77, true); store.releaseReady(1, ticket.getTicket(), 901);
            assertEquals(4, store.ticket(1).getState());
            sql("UPDATE player SET account='next-owner',owner_revision=2 WHERE player_id=1");
            assertEquals(terminal, store.commitOutcome(request)); store.finish(f, true);
            assertEquals(4, store.ticket(1).getState());
            assertThrows(RuntimeException.class, () -> store.commitOutcome(request.toBuilder().setTargetInstanceId("wrong-instance").build()));
        }
        @Test void 截止后六十秒宽限不授予ABORT_原COMPLETE仍唯一提交() throws Exception {
            var f=prepare(940);long deadline=store.now()-30_000;
            var original=f.toBuilder().setCreatedMs(1).setDeadlineMs(deadline).setCreateRequest(CreateBattleRequest.parseFrom(f.getCreateRequest()).toBuilder()
                    .setCreatedAtMs(1).setDeadlineMs(deadline).build().toByteString()).build();
            var registry=new com.game.pbmysql.PbMysql();registry.register(GlobalFormationRow.getDefaultInstance());
            try(var c=fixture.schemaConnection()){registry.updateByPk(c,original);}
            assertFalse(store.terminalDeadlineCandidates(0,0).battles().contains(940L));
            assertEquals(0,store.resolveTerminalAfterDeadline(940));
            assertEquals(1,store.commitOutcome(outcome(original)).getTerminalDecision());
            assertEquals(1,store.resolveTerminalAfterDeadline(940));
            assertEquals(2,new BattleCompletionStore(data).pendingPlayers(0,0).size());
        }
        @Test void 截止ABORT与COMPLETE竞争同formation_全员来源零份或两份不可半提交() throws Exception {
            var f = prepare(902);
            // 只推进本夹具持久截止，同时保持固定Create正文一致，不改服务器时钟或会话配置。
            var expired = f.toBuilder().setCreatedMs(1).setDeadlineMs(2).setCreateRequest(CreateBattleRequest.parseFrom(f.getCreateRequest()).toBuilder()
                    .setCreatedAtMs(1).setDeadlineMs(2).build().toByteString()).build();
            var db = new com.game.pbmysql.PbMysql(); db.register(GlobalFormationRow.getDefaultInstance());
            try (var c = fixture.schemaConnection()) { db.updateByPk(c, expired); }
            var start = new CyclicBarrier(2); var request = outcome(expired);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var complete = workers.submit(() -> { start.await(); return store.commitOutcome(request).getTerminalDecision(); });
                var abort = workers.submit(() -> { start.await(); return store.resolveTerminalAfterDeadline(902); });
                int a = complete.get(10, TimeUnit.SECONDS), b = abort.get(10, TimeUnit.SECONDS);
                assertEquals(a, b); assertTrue(a == 1 || a == 2);
                assertEquals(a == 1 ? 2 : 0, new BattleCompletionStore(data).pendingPlayers(0, 0).size());
                assertEquals(a, store.resolveTerminalAfterDeadline(902));
                if(a==2){assertDoesNotThrow(()->store.finish(expired,false));assertThrows(RuntimeException.class,()->store.finish(expired,true));}
            }
        }
        @Test void 普通PVE及PVP建房后Battle亡_无Guild取消也由正式轮询截止释放() throws Exception {
            List<GlobalFormationRow> originals=new ArrayList<>();
            for(int state=2;state<=4;state++) {
                int mode=state==2?4:state==3?3:5,count=mode==4?1:2;
                var f=prepare(910+state,10+state*2,mode,count);
                if(state==3)store.unknown(f);
                if(state==4){store.adopted(f,1,f.getInstanceId());store.finish(f,true);}
                originals.add(expire(store.formation(f.getBattleId())));
            }
            var complete=prepare(919);store.commitOutcome(outcome(complete));
            var completion=new BattleCompletionStore(data);
            try(var replay=new BattleCompletionReplay(completion,store,new Inbox(),(event,raw)->CompletableFuture.completedFuture(null),SECRET,()->1_000_000)) {
                replay.round();
                for(var f:originals) {
                    assertEquals(2,store.terminal(f.getBattleId()).getTerminalDecision());
                    assertEquals(5,store.formation(f.getBattleId()).getState());
                    for(var member:f.getMembersList())assertEquals(4,store.ticket(member.getPlayerId()).getState());
                }
                assertEquals(1,store.terminal(919).getTerminalDecision());assertEquals(3,store.ticket(1).getState());
                assertEquals(2,completion.pendingPlayers(0,0).size());
            }
        }
        @Test void 两恢复副本并发巡检_旧协议及实际adopted0保持UNKNOWN_未到截止不变() throws Exception {
            var due=expire(prepare(920));
            var legacy=expire(prepare(921,10,3,2));
            sql("UPDATE match_battle_completion SET outcome_protocol_version=0 WHERE battle_id=?",921);
            var observed=prepare(922,20,3,2);
            assertThrows(RuntimeException.class,()->store.adopted(observed,0,observed.getInstanceId()));expire(observed);
            var future=prepare(923,30,3,2);
            var completion=new BattleCompletionStore(data);
            try(var first=new BattleCompletionReplay(completion,store,new Inbox(),(event,raw)->CompletableFuture.completedFuture(null),SECRET,()->1_000_000);
                var second=new BattleCompletionReplay(completion,new GlobalMatchStore(data,new GlobalMatchProperties(true,true,30,180,120,SECRET)),
                        new Inbox(),(event,raw)->CompletableFuture.completedFuture(null),SECRET,()->1_000_000);
                var workers=Executors.newFixedThreadPool(2)) {
                var start=new CyclicBarrier(2);
                var a=workers.submit(()->{start.await();first.round();return null;});
                var b=workers.submit(()->{start.await();second.round();return null;});
                a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);
                assertEquals(2,store.terminal(due.getBattleId()).getTerminalDecision());
                for(var member:due.getMembersList())assertEquals(4,store.ticket(member.getPlayerId()).getState());
                for(var f:List.of(legacy,observed,future)) {
                    assertEquals(0,store.terminal(f.getBattleId()).getTerminalDecision());
                    for(var member:f.getMembersList())assertEquals(2,store.ticket(member.getPlayerId()).getState());
                }
                assertTrue(completion.pendingPlayers(0,0).isEmpty());
            }
        }

        @Test void 一人来源不符时整笔回滚_无原能力或旧协议不得追认() throws Exception {
            var f = prepare(903); var request = outcome(f);
            var event = BattleResultEvent.parseFrom(request.getRawResultPayload());
            var broken = event.toBuilder().setSettlements(1, event.getSettlements(1).toBuilder().setPlayerTeamIndex(0)).build();
            assertThrows(RuntimeException.class, () -> store.commitOutcome(request.toBuilder().setRawResultPayload(broken.toByteString()).build()));
            assertEquals(0, store.terminal(903).getTerminalDecision()); assertTrue(new BattleCompletionStore(data).pendingPlayers(0, 0).isEmpty());
            var shrunk=event.toBuilder().removeTeams(1).removeSettlements(1).build();
            assertEquals(1,BattleCompletionStore.validateTerminal(shrunk.toByteString()).size(),"缩小结果本身自洽，但不代表原Create全员");
            assertThrows(RuntimeException.class,()->store.commitOutcome(request.toBuilder().setRawResultPayload(shrunk.toByteString()).build()));
            assertEquals(0,store.terminal(903).getTerminalDecision());
            assertTrue(new BattleCompletionStore(data).pendingPlayers(0,0).isEmpty());
            assertEquals(2,store.ticket(1).getState());assertEquals(2,store.ticket(2).getState());
            assertThrows(RuntimeException.class,()->store.finish(f,false),"运行期ABORTED不能代SQL ABORT清票或重排");
            assertEquals(0,store.terminal(903).getTerminalDecision());
            assertEquals(2,store.ticket(1).getState());assertEquals(2,store.ticket(2).getState());
            assertThrows(RuntimeException.class, () -> store.saveIntent(f, CreateBattleRequest.newBuilder().setOutcomeProtocolVersion(1).build().toByteArray()));
            assertThrows(RuntimeException.class, () -> store.adopted(f, 0, f.getInstanceId()));
            assertEquals(0, store.terminal(903).getAdoptedOutcomeProtocolVersion());
            assertTrue(store.terminal(903).getAdoptedOutcomeObserved());
            assertThrows(RuntimeException.class, () -> store.adopted(f, 1, f.getInstanceId()));
            assertThrows(RuntimeException.class, () -> store.commitOutcome(request));
            assertThrows(RuntimeException.class, () -> store.resolveTerminalAfterDeadline(903));
            assertThrows(RuntimeException.class, () -> store.finish(f, false));
            assertEquals(0, store.terminal(903).getTerminalDecision());
        }

        @Test void COMPLETE提交重核固定Create全部范围及时间_坏正文不生成个人来源() throws Exception {
            var f = prepare(904); var original = CreateBattleRequest.parseFrom(f.getCreateRequest()); var request = outcome(f);
            var variants = List.of(original.toBuilder().setBattleConfigId(9).build(), original.toBuilder().setMatchMode(4).build(),
                    original.toBuilder().setCreatedAtMs(0).build(), original.toBuilder().setCreatedAtMs(f.getCreatedMs() - 1).build(),
                    original.toBuilder().setDeadlineMs(original.getCreatedAtMs()).build(), original.toBuilder().setRequiredPhysicalRegion(2).build(),
                    original.toBuilder().setPlayers(1, original.getPlayers(0)).build(),
                    original.toBuilder().setPlayers(1, original.getPlayers(1).toBuilder().setTeamIndex(0)).build());
            var db = new com.game.pbmysql.PbMysql(); db.register(GlobalFormationRow.getDefaultInstance());
            for (var changed : variants) {
                try (var c = fixture.schemaConnection()) { db.updateByPk(c, f.toBuilder().setCreateRequest(changed.toByteString()).build()); }
                assertThrows(RuntimeException.class, () -> store.commitOutcome(request));
                assertEquals(0, store.terminal(904).getTerminalDecision());
                assertTrue(new BattleCompletionStore(data).pendingPlayers(0, 0).isEmpty());
            }
            try (var c = fixture.schemaConnection()) { db.updateByPk(c, f); }
            var beforeBirth = BattleResultEvent.parseFrom(request.getRawResultPayload()).toBuilder().setFinishedAtMs(original.getCreatedAtMs() - 1).build();
            assertThrows(RuntimeException.class, () -> store.commitOutcome(request.toBuilder().setRawResultPayload(beforeBirth.toByteString()).build()));
            assertEquals(1, store.commitOutcome(request).getTerminalDecision());
        }
    }
}
