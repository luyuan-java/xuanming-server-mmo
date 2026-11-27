package com.game.match.feature;

import com.game.api.ContentGuardService;
import com.game.api.DubboGroups;
import com.game.api.FriendInternalService;
import com.game.api.MailAdminService;
import com.game.api.rpc.NodeRpcClients;
import com.game.common.deadline.Deadline;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.gather.GatherLauncher;
import com.game.match.gather.GatherPlan;
import com.game.match.global.GlobalMatchService;
import com.game.match.global.pb.GlobalDirectIntentRow;
import com.game.match.global.pb.GlobalFormationRow;
import com.game.match.id.MatchIds;
import com.game.match.jianghu.JianghuDuelService;
import com.game.match.precheck.MemberPrecheck;
import com.game.match.ranked.RankedService;
import com.game.match.ranked.RankedStore;
import com.game.match.ranked.RankedTables;
import com.game.match.rating.MatchRatingTables;
import com.game.match.rating.RatingConfiguration;
import com.game.match.report.BattleReportHandlers;
import com.game.match.report.BattleReportStore;
import com.game.match.report.TrialChallengeCardHandlers;
import com.game.match.report.TrialChallengeCardStore;
import com.game.match.script.BattleScriptCatalog;
import com.game.match.script.BattleScriptResolver;
import com.game.match.script.BattleScriptService;
import com.game.match.script.BattleScriptStore;
import com.game.match.script.BattleScriptValidator;
import com.game.match.tournament.TournamentSeasonDriver;
import com.game.match.tournament.TournamentService;
import com.game.match.tournament.TournamentStore;
import com.game.match.tournament.TournamentTables;
import com.game.proto.content_guard.CheckTextRequest;
import com.game.proto.mail.MailAttachments;
import com.game.proto.mail.SendMailRequest;
import com.game.proto.mail.SendMailResponse;
import com.game.proto.match.MatchMode;
import com.game.table.ConfigTables;
import com.google.protobuf.ByteString;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 战报回放（379/380）、战斗剧本与审核（387–392）、论剑排位预设档案（448–451）统一装配。 */
@Configuration(proxyBeanMethods = false)
public class MatchFeatureConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MatchFeatureConfiguration.class);

    public record FeatureSchemaReady() {
    }

    @Bean
    public FeatureSchemaReady matchFeatureSchema(DataSource dataSource,
                                                 ObjectProvider<MatchFeatureTables.SchemaSync> featureSync,
                                                 ObjectProvider<MatchRatingTables.SchemaSync> ratingSync,
                                                 RatingConfiguration.SchemaReady ratingReady) throws SQLException {
        MatchFeatureTables.SchemaSync custom = featureSync.getIfAvailable();
        if (custom != null) {
            custom.sync(dataSource);
        } else if (ratingSync.getIfAvailable() == null) {
            MatchFeatureTables.PBMYSQL.sync(dataSource);
        }
        log.info("战报/战斗剧本/排位权威表已就绪");
        return new FeatureSchemaReady();
    }

    // ---- 战报回放（379/380） ----

    @Bean
    public BattleReportStore battleReportStore(DataSource dataSource, TournamentStore tournamentStore,
                                               RankedStore rankedStore,
                                               FeatureSchemaReady schemaReady) {
        return new BattleReportStore(dataSource,
                battleId -> tournamentStore.isPublicKnockoutReplayAllowed(battleId)
                        || rankedStore.isFeaturedRankedReplayAllowed(battleId));
    }

    @Bean
    public MatchMethodHandler listBattleReportsHandler(BattleReportStore store) {
        return BattleReportHandlers.listHandler(store);
    }

    @Bean
    public MatchMethodHandler getBattleReportHandler(BattleReportStore store) {
        return BattleReportHandlers.getHandler(store);
    }

    // ---- 五行试炼志 V3 好友约战帖与受控 PVE 录像播放（570–572） ----

    @Bean
    public TrialChallengeCardStore trialChallengeCardStore(DataSource dataSource, FeatureSchemaReady schemaReady) {
        return new TrialChallengeCardStore(dataSource, System::currentTimeMillis);
    }

    @Bean
    public MatchMethodHandler createTrialChallengeCardHandler(TrialChallengeCardStore store, MatchIds matchIds) {
        return TrialChallengeCardHandlers.createHandler(store, () -> matchIds.nextBattleId().orElse(0L));
    }

    @Bean
    public MatchMethodHandler getTrialChallengeCardHandler(TrialChallengeCardStore store) {
        return TrialChallengeCardHandlers.getHandler(store);
    }

    @Bean
    public MatchMethodHandler getSharedTrialReplayHandler(TrialChallengeCardStore store) {
        return TrialChallengeCardHandlers.sharedReplayHandler(store);
    }

    // ---- 战斗剧本与运营审核（387–392） ----

    @Bean
    public BattleScriptValidator.Tables battleScriptTables(ConfigTables config) {
        return BattleScriptValidator.tablesFrom(config);
    }

    @Bean
    public BattleScriptCatalog battleScriptCatalog(BattleScriptValidator.Tables tables) {
        BattleScriptCatalog catalog = BattleScriptCatalog.loadOfficial(tables);
        log.info("官方战斗剧本已加载: scripts={}", catalog.officialScripts().size());
        return catalog;
    }

    @Bean
    public BattleScriptStore battleScriptStore(DataSource dataSource, FeatureSchemaReady schemaReady) {
        return new BattleScriptStore(dataSource, System::currentTimeMillis);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public com.game.match.script.BattleScriptReviewCleanup battleScriptReviewCleanup(
            BattleScriptStore store,
            org.springframework.beans.factory.ObjectProvider<com.game.match.MatchProperties> propsProvider,
            org.springframework.beans.factory.ObjectProvider<com.game.match.metrics.MatchMetrics> metricsProvider) {
        com.game.match.MatchProperties props = propsProvider.getIfAvailable();
        int retentionDays = props == null
                ? com.game.match.MatchProperties.DEFAULT_SCRIPT_REVIEW_RETENTION_DAYS
                : props.scriptReviewRetentionDays();
        return new com.game.match.script.BattleScriptReviewCleanup(
                store, retentionDays, metricsProvider.getIfAvailable(), System::currentTimeMillis);
    }

    @Bean
    public BattleScriptResolver battleScriptResolver(BattleScriptCatalog catalog,
                                                     BattleScriptValidator.Tables tables,
                                                     BattleScriptStore store) {
        return new BattleScriptResolver(catalog, tables, store);
    }

    @Bean
    public BattleScriptService battleScriptService(BattleScriptStore store, BattleScriptCatalog catalog,
                                                   BattleScriptValidator.Tables tables, MatchIds matchIds) {
        return new BattleScriptService(store, catalog, tables, () -> matchIds.nextBattleId().orElse(0L));
    }

    @Bean
    public MatchMethodHandler listBattleScriptsHandler(BattleScriptService service) {
        return service.clientHandlers().get(0);
    }

    @Bean
    public MatchMethodHandler submitBattleScriptHandler(BattleScriptService service) {
        return service.clientHandlers().get(1);
    }

    @Bean
    public MatchMethodHandler deleteBattleScriptHandler(BattleScriptService service) {
        return service.clientHandlers().get(2);
    }

    @Bean
    public MatchMethodHandler setBattleScriptLoadoutHandler(BattleScriptService service) {
        return service.clientHandlers().get(3);
    }

    // ---- 论剑排位首发 V1（448–451） ----

    @Bean
    public RankedTables rankedTables(ConfigTables config) {
        return RankedTables.from(config);
    }

    @Bean
    public RankedStore rankedStore(DataSource dataSource, RankedTables tables, FeatureSchemaReady schemaReady) {
        return new RankedStore(dataSource, tables, System::currentTimeMillis);
    }

    @Bean
    public RankedService rankedService(RankedStore store, RankedTables tables) {
        return new RankedService(store, tables, System::currentTimeMillis);
    }

    /** 论剑排位换季恢复与自动推进后台驱动（每 60s 幂等检查 SETTLING 或已过 end_ms 赛季，按需仅查榜首 1 人昵称）。 */
    @Bean
    public org.springframework.context.SmartLifecycle rankedSeasonRolloverDriver(
            RankedStore rankedStore, ObjectProvider<DataSource> dataSourceProvider) {
        DataSource ds = dataSourceProvider.getIfAvailable();
        java.util.function.LongFunction<String> championNameLookup = championId -> {
            if (ds == null || championId == 0L) {
                return "";
            }
            com.game.common.player.PlayerProfiles.ConnectionSource conns =
                    ds instanceof com.alibaba.druid.pool.DruidDataSource pool
                            ? pool::getConnection
                            : wait -> ds.getConnection();
            var profiles = new com.game.common.player.PlayerProfiles(conns, 2);
            var profile = profiles.load(List.of(championId), Deadline.after(2_000)).get(championId);
            return profile == null ? "" : profile.name();
        };
        return new org.springframework.context.SmartLifecycle() {
            private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();
            private java.util.concurrent.ScheduledExecutorService scheduler;

            @Override
            public synchronized void start() {
                if (!running.compareAndSet(false, true)) {
                    return;
                }
                scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().name("ranked-season-rollover").daemon(true).factory());
                scheduler.scheduleWithFixedDelay(() -> {
                    if (!running.get()) {
                        return;
                    }
                    try {
                        rankedStore.recoverSeasonRollover(championNameLookup, null);
                    } catch (Exception e) {
                        log.warn("[ranked] season rollover recovery tick failed: {}", e.getMessage());
                    }
                }, 5_000L, 60_000L, TimeUnit.MILLISECONDS);
            }

            @Override
            public synchronized void stop() {
                running.set(false);
                if (scheduler != null) {
                    scheduler.shutdownNow();
                    scheduler = null;
                }
            }

            @Override
            public boolean isRunning() {
                return running.get();
            }
        };
    }

    @Bean
    public MatchMethodHandler getRankedProfileHandler(RankedService service) {
        return service.clientHandlers().get(0);
    }

    @Bean
    public MatchMethodHandler saveRankedPresetHandler(RankedService service) {
        return service.clientHandlers().get(1);
    }

    @Bean
    public MatchMethodHandler updateRankedCosmeticHandler(RankedService service) {
        return service.clientHandlers().get(2);
    }

    @Bean
    public MatchMethodHandler getRankedBattleResultHandler(RankedService service) {
        return service.clientHandlers().get(3);
    }

    // ---- 全服赛季战队锦标赛《群仙问道赛》V1（465–475） ----

    private static final int TOURNAMENT_MAIL_SENDER_KIND = 6; // 对应 game-mail SenderKind("match") = 6
    private static final String TOURNAMENT_MAIL_CALLER = "match";
    private static final long TOURNAMENT_MAIL_TTL_MS = 30L * 24 * 3600 * 1000;

    @Bean
    public TournamentTables tournamentTables(ConfigTables config) {
        return TournamentTables.from(config);
    }

    @Bean
    public TournamentStore tournamentStore(DataSource dataSource, TournamentTables tables, FeatureSchemaReady schemaReady) {
        return new TournamentStore(dataSource, tables, System::currentTimeMillis);
    }

    @Bean
    public TournamentService.MatchLauncher tournamentMatchLauncher(
            ObjectProvider<GlobalMatchService> globalMatchProvider,
            GatherLauncher gatherLauncher,
            MemberPrecheck memberPrecheck,
            MatchIds matchIds,
            TournamentTables tournamentTables) {
        return spec -> {
            List<Long> members = new ArrayList<>(spec.teamA().size() + spec.teamB().size());
            members.addAll(spec.teamA());
            members.addAll(spec.teamB());
            int banSlots = tournamentTables.rule().getSkillBanSlotsPerTeam();
            GlobalMatchService global = globalMatchProvider.getIfAvailable();
            if (global != null) {
                try {
                    GlobalDirectIntentRow key = GlobalDirectIntentRow.newBuilder()
                            .setKind(4)
                            .setOwnerId(spec.matchId())
                            .setRequestLow(spec.matchId())
                            .setRequestHigh(Math.max(1, spec.gameIndex()))
                            .build();
                    ByteString payload = encodeTournamentLaunchPayload(spec);
                    GlobalFormationRow formation = global.reserveDirect(
                            key, payload, MatchMode.MATCH_MODE_SEASON_TOURNAMENT_VALUE,
                            spec.battleConfigId(), members, Map.of(), 0L, null, spec.toBattleContext(banSlots));
                    if (formation == null || formation.getBattleId() == 0L || formation.getState() == 5) {
                        return 0L;
                    }
                    if (formation.getState() < 4) {
                        global.launchCommitted(formation);
                    }
                    return formation.getBattleId();
                } catch (RuntimeException e) {
                    log.warn("[tournament] global reserveDirect failed match={}: {}",
                            Long.toUnsignedString(spec.matchId()), e.getMessage());
                    return 0L;
                }
            }
            MemberPrecheck.Result checked = memberPrecheck.check(members, Deadline.after(3_000));
            if (!checked.passed()) {
                log.warn("[tournament] local precheck failed match={} reason={} offender={}",
                        Long.toUnsignedString(spec.matchId()), checked.reason(), Long.toUnsignedString(checked.offender()));
                return 0L;
            }
            long battleId = matchIds.nextBattleId().orElse(0L);
            if (battleId == 0L) {
                log.error("[tournament] local battle_id allocation failed match={}", Long.toUnsignedString(spec.matchId()));
                return 0L;
            }
            gatherLauncher.launch(GatherPlan.tournament(spec.battleConfigId(), members, battleId, spec.toBattleContext(banSlots)))
                    .whenComplete((result, err) -> {
                        boolean ok = err == null && result != null && result.ok();
                        if (!ok) {
                            log.error("[tournament] local gather failed match={} battle={} outcome={}",
                                    Long.toUnsignedString(spec.matchId()), Long.toUnsignedString(battleId),
                                    result == null ? "error" : result.outcome().label(), err);
                        }
                    });
            return battleId;
        };
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "game.match.content-guard-enabled", havingValue = "true")
    public NodeRpcClients<ContentGuardService> tournamentContentGuardClients() {
        return new NodeRpcClients<>("match-content-guard", ContentGuardService.class,
                DubboGroups.CONTENT_GUARD, Duration.ofMillis(1500), "match-content-guard-connect");
    }

    @Bean
    @ConditionalOnProperty(name = "game.match.content-guard-enabled", havingValue = "true")
    public TournamentService.TextGuard tournamentTextGuard(
            @Qualifier("tournamentContentGuardClients") NodeRpcClients<ContentGuardService> clients,
            @Value("${game.dubbo.content-guard-url:tri://127.0.0.1:20919}") String url) {
        NodeRpcClients.Target target = parseTriTarget("game.dubbo.content-guard-url", url);
        Duration timeout = Duration.ofMillis(1500);
        return (scene, playerId, text) -> {
            CheckTextRequest req = CheckTextRequest.newBuilder()
                    .setScene(scene).setPlayerId(playerId).setText(text).build();
            try {
                return clients.call(target, timeout, svc -> CompletableFuture.completedFuture(svc.checkText(req)))
                        .get(timeout.toMillis() + 300L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("tournament content guard interrupted", e);
            } catch (Exception e) {
                throw new IllegalStateException("tournament content guard failed: " + e.getMessage(), e);
            }
        };
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "game.match.mail.enabled", havingValue = "true")
    public NodeRpcClients<MailAdminService> tournamentMailClients() {
        return new NodeRpcClients<>("match-mail-admin", MailAdminService.class,
                DubboGroups.MAIL, Duration.ofMillis(2000), "match-mail-connect");
    }

    @Bean
    @ConditionalOnProperty(name = "game.match.mail.enabled", havingValue = "true")
    public TournamentService.RewardMailSender tournamentRewardMailSender(
            @Qualifier("tournamentMailClients") NodeRpcClients<MailAdminService> clients,
            @Value("${game.match.mail-url:tri://127.0.0.1:20916}") String url,
            @Value("${game.match.mail-producer-secret:${MAIL_PRODUCER_SECRET_MATCH:}}") String secret) {
        String trimmedSecret = secret == null ? "" : secret.strip();
        if (trimmedSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("启用 game.match.mail.enabled 时必须配置 ≥32 字节的 MAIL_PRODUCER_SECRET_MATCH");
        }
        NodeRpcClients.Target target = parseTriTarget("game.match.mail-url", url);
        Duration timeout = Duration.ofMillis(2000);
        return (playerId, reward, title, body) -> {
            MailAttachments atts = reward.attachments().length > 0
                    ? MailAttachments.parseFrom(reward.attachments())
                    : MailAttachments.getDefaultInstance();
            // expire_ms 置 0 由 game-mail 缺省补 30 天，保证重试摘要确定性且不受跨节点时钟偏移影响。
            long nowMs = System.currentTimeMillis();
            SendMailRequest request = SendMailRequest.newBuilder()
                    .setSenderKind(TOURNAMENT_MAIL_SENDER_KIND)
                    .setRequestId(reward.rewardId())
                    .setRecipientPlayerId(playerId)
                    .setTitle(title)
                    .setBody(body)
                    .setAttachments(atts)
                    .setExpireMs(0L)
                    .build();
            SendMailRequest signed = ServiceCallSignatures.sign(
                    MailAdminService.PERSONAL_METHOD, request, TOURNAMENT_MAIL_CALLER, trimmedSecret, nowMs);
            SendMailResponse response = clients.call(target, timeout, svc -> svc.sendPersonalMail(signed))
                    .get(timeout.toMillis() + 300L, TimeUnit.MILLISECONDS);
            if (response == null || response.getErrorMessage().getId() != 0 || response.getMailId() == 0L) {
                int tipId = response == null ? -1 : response.getErrorMessage().getId();
                if (tipId > 0) {
                    throw new TournamentService.MailRejectedException(tipId);
                }
                throw new IllegalStateException("mail admin rejected tip=" + tipId);
            }
            return response.getMailId();
        };
    }

    @Bean
    public TournamentService tournamentService(TournamentStore store, MatchIds matchIds,
                                               ObjectProvider<TournamentService.MatchLauncher> launcher,
                                               ObjectProvider<TournamentService.TextGuard> textGuard,
                                               ObjectProvider<TournamentService.RewardMailSender> mailSender,
                                               ObjectProvider<com.game.common.RunMode> runModeProvider) {
        TournamentService.TextGuard effectiveGuard = textGuard.getIfAvailable();
        com.game.common.RunMode runMode = runModeProvider.getIfAvailable(() -> com.game.common.RunMode.PROD);
        if (effectiveGuard == null && runMode == com.game.common.RunMode.PROD) {
            log.warn("[tournament] prod 模式下未启用 game.match.content-guard-enabled，UGC 战队名审核将 fail-closed 拒收");
            effectiveGuard = (scene, playerId, text) -> {
                throw new IllegalStateException("content guard disabled in prod");
            };
        }
        return new TournamentService(store, () -> matchIds.nextBattleId().orElse(0L),
                launcher.getIfAvailable(), effectiveGuard, mailSender.getIfAvailable());
    }

    @Bean
    public TournamentSeasonDriver tournamentSeasonDriver(TournamentStore store, MatchIds matchIds,
                                                         TournamentService tournamentService) {
        return new TournamentSeasonDriver(store, () -> matchIds.nextBattleId().orElse(0L),
                5_000L, tournamentService::launchSpec);
    }

    private static ByteString encodeTournamentLaunchPayload(TournamentStore.LaunchSpec spec) {
        ByteBuffer buf = ByteBuffer.allocate(20 + 8 * (spec.teamA().size() + spec.teamB().size()))
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(spec.matchId());
        buf.putInt(spec.seasonId());
        buf.putInt(spec.battleConfigId());
        buf.putInt(Math.max(1, spec.gameIndex()));
        for (long pid : spec.teamA()) {
            buf.putLong(pid);
        }
        for (long pid : spec.teamB()) {
            buf.putLong(pid);
        }
        return ByteString.copyFrom(buf.array());
    }

    private static NodeRpcClients.Target parseTriTarget(String propName, String url) {
        URI uri = URI.create(url == null ? "" : url.trim());
        if (!"tri".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1) {
            throw new IllegalArgumentException(propName + " 必须是 tri://host:port: " + url);
        }
        return new NodeRpcClients.Target(uri.getHost(), uri.getPort(), "");
    }

    @Bean
    public MatchMethodHandler getTournamentOverviewHandler(TournamentService service) {
        return service.clientHandlers().get(0);
    }

    @Bean
    public MatchMethodHandler createTournamentSquadHandler(TournamentService service) {
        return service.clientHandlers().get(1);
    }

    @Bean
    public MatchMethodHandler inviteTournamentMemberHandler(TournamentService service) {
        return service.clientHandlers().get(2);
    }

    @Bean
    public MatchMethodHandler respondTournamentInviteHandler(TournamentService service) {
        return service.clientHandlers().get(3);
    }

    @Bean
    public MatchMethodHandler leaveTournamentSquadHandler(TournamentService service) {
        return service.clientHandlers().get(4);
    }

    @Bean
    public MatchMethodHandler updateTournamentLineupHandler(TournamentService service) {
        return service.clientHandlers().get(5);
    }

    @Bean
    public MatchMethodHandler registerTournamentSquadHandler(TournamentService service) {
        return service.clientHandlers().get(6);
    }

    @Bean
    public MatchMethodHandler listTournamentStandingsHandler(TournamentService service) {
        return service.clientHandlers().get(7);
    }

    @Bean
    public MatchMethodHandler startTournamentMatchHandler(TournamentService service) {
        return service.clientHandlers().get(8);
    }

    @Bean
    public MatchMethodHandler cheerTournamentSquadHandler(TournamentService service) {
        return service.clientHandlers().get(9);
    }

    @Bean
    public MatchMethodHandler claimTournamentRewardHandler(TournamentService service) {
        return service.clientHandlers().get(10);
    }

    @Bean
    public MatchMethodHandler sendTournamentSpectateCheerHandler(TournamentService service) {
        return service.clientHandlers().get(11);
    }

    @Bean
    public MatchMethodHandler getTournamentSpectateCheerHandler(TournamentService service) {
        return service.clientHandlers().get(12);
    }

    @Bean
    public MatchMethodHandler updateTournamentSquadEmblemHandler(TournamentService service) {
        return service.clientHandlers().get(13);
    }

    @Bean
    public MatchMethodHandler startTournamentWarmupHandler(TournamentService service) {
        return service.clientHandlers().get(14);
    }

    @Bean
    public MatchMethodHandler predictTournamentMatchHandler(TournamentService service) {
        return service.clientHandlers().get(15);
    }

    @Bean
    public MatchMethodHandler getTournamentPredictionBoardHandler(TournamentService service) {
        return service.clientHandlers().get(16);
    }

    @Bean
    public MatchMethodHandler banTournamentLeylineHandler(TournamentService service) {
        return service.clientHandlers().get(17);
    }

    @Bean
    public MatchMethodHandler getTournamentHighlightsHandler(TournamentService service) {
        return service.clientHandlers().get(18);
    }

    // ---- 江湖恩怨与天墉生死状一期(548..554) ----

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "game.match.friend-enabled", havingValue = "true")
    public NodeRpcClients<FriendInternalService> matchFriendClients() {
        return new NodeRpcClients<>("match-friend-internal", FriendInternalService.class,
                DubboGroups.FRIEND, Duration.ofMillis(1500), "match-friend-connect");
    }

    @Bean
    public JianghuDuelService jianghuDuelService(
            ConfigTables config,
            MatchIds matchIds,
            ObjectProvider<DataSource> dataSourceProvider,
            ObjectProvider<org.redisson.api.RedissonClient> redisProvider,
            ObjectProvider<com.game.discovery.RedisKeys> redisKeysProvider,
            ObjectProvider<com.game.discovery.presence.PlayerPresenceDirectory> presenceProvider,
            ObjectProvider<com.game.discovery.battle.BattleLockReader> battleLockProvider,
            ObjectProvider<GatherLauncher> gatherLauncherProvider,
            ObjectProvider<MemberPrecheck> memberPrecheckProvider,
            ObjectProvider<NodeRpcClients<FriendInternalService>> friendClientsProvider,
            @Value("${game.dubbo.friend-url:tri://127.0.0.1:20883}") String friendUrl) {
        DataSource ds = dataSourceProvider.getIfAvailable();
        org.redisson.api.RedissonClient redis = redisProvider.getIfAvailable();
        com.game.discovery.RedisKeys redisKeys = redisKeysProvider.getIfAvailable();
        com.game.discovery.team.TeamMembershipReader teamReader = (redis != null && redisKeys != null)
                ? new com.game.discovery.team.TeamMembershipReader(redis, redisKeys) : null;
        com.game.discovery.presence.PlayerPresenceDirectory presence = presenceProvider.getIfAvailable();
        com.game.discovery.battle.BattleLockReader battleLocks = battleLockProvider.getIfAvailable();
        GatherLauncher gatherLauncher = gatherLauncherProvider.getIfAvailable();
        MemberPrecheck precheck = memberPrecheckProvider.getIfAvailable();
        NodeRpcClients<FriendInternalService> friendClients = friendClientsProvider.getIfAvailable();
        NodeRpcClients.Target friendTarget = friendClients == null ? null : parseTriTarget("game.dubbo.friend-url", friendUrl);
        com.game.common.player.PlayerProfiles profiles;
        if (ds != null) {
            com.game.common.player.PlayerProfiles.ConnectionSource conns =
                    ds instanceof com.alibaba.druid.pool.DruidDataSource pool
                            ? pool::getConnection
                            : wait -> ds.getConnection();
            profiles = new com.game.common.player.PlayerProfiles(conns, 1);
        } else {
            profiles = null;
        }
        JianghuDuelService.Ports ports = new JianghuDuelService.Ports() {
            @Override
            public java.util.Optional<List<Long>> authoritativeTeamOf(long captainPlayerId) {
                if (teamReader == null) {
                    return java.util.Optional.empty();
                }
                try {
                    com.game.discovery.team.TeamMembership m = teamReader.readAsync(captainPlayerId)
                            .get(1_000L, TimeUnit.MILLISECONDS);
                    if (m == null || !m.inTeam() || m.info() == null) {
                        return java.util.Optional.empty();
                    }
                    if (m.info().getLeaderId() != captainPlayerId) {
                        return java.util.Optional.of(List.of());
                    }
                    List<Long> ordered = new ArrayList<>();
                    ordered.add(captainPlayerId);
                    for (long pid : m.info().getMembersList()) {
                        if (pid != captainPlayerId) {
                            ordered.add(pid);
                        }
                    }
                    return java.util.Optional.of(ordered);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("读取小队成员被中断", e);
                } catch (Exception e) {
                    throw new IllegalStateException("读取小队成员失败: " + e.getMessage(), e);
                }
            }

            @Override
            public boolean isPlayerOnline(long playerId) {
                if (presence == null) {
                    return true;
                }
                try {
                    return presence.findStrictAsync(playerId).toCompletableFuture()
                            .get(800L, TimeUnit.MILLISECONDS).isPresent();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public boolean isPlayerInBattle(long playerId) {
                if (battleLocks == null) {
                    return false;
                }
                try {
                    Boolean locked = battleLocks.exists(playerId).get(800L, TimeUnit.MILLISECONDS);
                    return Boolean.TRUE.equals(locked);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return true;
                } catch (Exception e) {
                    return true;
                }
            }

            @Override
            public JianghuDuelService.SocialRelationCheck checkSocialRelations(List<Long> sideA, List<Long> sideB) {
                if (friendClients == null || friendTarget == null) {
                    // shortcut: 未启用 game.match.friend-enabled 时无法调好友服，生产部署须开启并配 game.dubbo.friend-url
                    return new JianghuDuelService.SocialRelationCheck(false, false);
                }
                var req = com.game.proto.friend.BatchCheckSocialRelationsRequest.newBuilder()
                        .addAllSideAPlayerIds(sideA)
                        .addAllSideBPlayerIds(sideB)
                        .build();
                try {
                    var resp = friendClients.call(friendTarget, Duration.ofMillis(1500),
                                    svc -> svc.batchCheckSocialRelations(req))
                            .get(1_800L, TimeUnit.MILLISECONDS);
                    if (resp == null || (resp.hasErrorMessage() && resp.getErrorMessage().getId() != 0)) {
                        throw new IllegalStateException("好友关系批量校验失败");
                    }
                    return new JianghuDuelService.SocialRelationCheck(resp.getHasAnyBlock(), resp.getAllAreFriends());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("好友关系批量校验被中断", e);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException("好友关系批量校验异常: " + e.getMessage(), e);
                }
            }

            @Override
            public String displayName(long playerId) {
                if (profiles == null) {
                    return "道友" + Long.toUnsignedString(playerId);
                }
                var p = profiles.load(List.of(playerId), Deadline.after(1_000)).get(playerId);
                return (p == null || p.name().isEmpty()) ? "道友" + Long.toUnsignedString(playerId) : p.name();
            }

            @Override
            public JianghuDuelService.GuildSummary pureGuildOf(List<Long> members) {
                if (ds == null || members == null || members.isEmpty()) {
                    return JianghuDuelService.GuildSummary.NONE;
                }
                try (java.sql.Connection c = ds.getConnection()) {
                    long commonGuildId = 0L;
                    try (java.sql.PreparedStatement ps = c.prepareStatement(
                            "SELECT guild_id FROM guild_member WHERE player_id=?")) {
                        ps.setQueryTimeout(1);
                        for (long pid : members) {
                            ps.setObject(1, com.game.pbmysql.PbMysql.uint64(pid));
                            try (java.sql.ResultSet rs = ps.executeQuery()) {
                                if (!rs.next()) {
                                    return JianghuDuelService.GuildSummary.NONE;
                                }
                                long gid = rs.getLong(1);
                                if (gid == 0L) {
                                    return JianghuDuelService.GuildSummary.NONE;
                                }
                                if (commonGuildId == 0L) {
                                    commonGuildId = gid;
                                } else if (commonGuildId != gid) {
                                    return JianghuDuelService.GuildSummary.NONE;
                                }
                            }
                        }
                    }
                    if (commonGuildId == 0L) {
                        return JianghuDuelService.GuildSummary.NONE;
                    }
                    try (java.sql.PreparedStatement ps = c.prepareStatement(
                            "SELECT name FROM guild WHERE guild_id=?")) {
                        ps.setQueryTimeout(1);
                        ps.setObject(1, com.game.pbmysql.PbMysql.uint64(commonGuildId));
                        try (java.sql.ResultSet rs = ps.executeQuery()) {
                            String name = rs.next() && rs.getString(1) != null ? rs.getString(1) : "";
                            return new JianghuDuelService.GuildSummary(commonGuildId, name);
                        }
                    }
                } catch (SQLException e) {
                    log.debug("[jianghu] pureGuildOf query unavailable: {}", e.getMessage());
                    return JianghuDuelService.GuildSummary.NONE;
                }
            }

            @Override
            public long launchBattle(com.game.proto.match.JianghuDuelInviteView invite,
                                     com.game.proto.JianghuDuelBattleContext duelContext) {
                if (gatherLauncher == null) {
                    return 0L;
                }
                List<Long> roster = new ArrayList<>(
                        invite.getChallengerMembersCount() + invite.getTargetMembersCount());
                for (var m : invite.getChallengerMembersList()) {
                    roster.add(m.getPlayerId());
                }
                for (var m : invite.getTargetMembersList()) {
                    roster.add(m.getPlayerId());
                }
                if (precheck != null) {
                    MemberPrecheck.Result checked = precheck.check(roster, Deadline.after(3_000));
                    if (!checked.passed()) {
                        log.warn("[jianghu] precheck failed invite={} reason={} offender={}",
                                Long.toUnsignedString(invite.getDuelInviteId()), checked.reason(),
                                Long.toUnsignedString(checked.offender()));
                        return 0L;
                    }
                }
                long battleId = matchIds.nextBattleId().orElse(0L);
                if (battleId == 0L) {
                    log.error("[jianghu] battle_id allocation failed invite={}",
                            Long.toUnsignedString(invite.getDuelInviteId()));
                    return 0L;
                }
                gatherLauncher.launch(GatherPlan.jianghuDuel(0, roster, battleId, duelContext))
                        .whenComplete((result, err) -> {
                            boolean ok = err == null && result != null && result.ok();
                            if (!ok) {
                                log.error("[jianghu] gather launch failed invite={} battle={} outcome={}",
                                        Long.toUnsignedString(invite.getDuelInviteId()),
                                        Long.toUnsignedString(battleId),
                                        result == null ? "error" : result.outcome().label(), err);
                            }
                        });
                return battleId;
            }
        };
        return new JianghuDuelService(
                () -> config.jianghuRule().find(1).orElse(null),
                ports,
                System::currentTimeMillis,
                () -> matchIds.nextBattleId().orElse(0L));
    }

    @Bean
    public MatchMethodHandler createJianghuDuelInviteHandler(JianghuDuelService service) {
        return service.clientHandlers().get(0);
    }

    @Bean
    public MatchMethodHandler confirmJianghuDuelMemberHandler(JianghuDuelService service) {
        return service.clientHandlers().get(1);
    }

    @Bean
    public MatchMethodHandler respondJianghuDuelInviteHandler(JianghuDuelService service) {
        return service.clientHandlers().get(2);
    }

    @Bean
    public MatchMethodHandler listJianghuGrudgesHandler(JianghuDuelService service) {
        return service.clientHandlers().get(3);
    }

    @Bean
    public MatchMethodHandler dismissJianghuGrudgeHandler(JianghuDuelService service) {
        return service.clientHandlers().get(4);
    }

    @Bean
    public MatchMethodHandler notifyJianghuDuelInviteHandler(JianghuDuelService service) {
        return service.clientHandlers().get(5);
    }

    @Bean
    public MatchMethodHandler notifyJianghuDuelStatusHandler(JianghuDuelService service) {
        return service.clientHandlers().get(6);
    }

    // ---- 万象残局坊 V1（575..581） ----

    @Bean
    public com.game.match.puzzle.TacticalPuzzleStore tacticalPuzzleStore(
            DataSource dataSource, FeatureSchemaReady schemaReady) {
        return new com.game.match.puzzle.TacticalPuzzleStore(dataSource, System::currentTimeMillis);
    }

    @Bean
    public com.game.match.puzzle.TacticalPuzzleService tacticalPuzzleService(
            com.game.match.puzzle.TacticalPuzzleStore store,
            MatchIds matchIds,
            ObjectProvider<TournamentService.TextGuard> tournamentTextGuard,
            ObjectProvider<GatherLauncher> gatherLauncher,
            ObjectProvider<RankedTables> rankedTables,
            ObjectProvider<com.game.common.RunMode> runModeProvider) {
        TournamentService.TextGuard guard = tournamentTextGuard.getIfAvailable();
        com.game.common.RunMode runMode = runModeProvider.getIfAvailable(() -> com.game.common.RunMode.PROD);
        com.game.match.puzzle.TacticalPuzzleService.TextGuard puzzleGuard;
        if (guard != null) {
            puzzleGuard = (playerId, text) -> {
                try {
                    var resp = guard.check(com.game.proto.content_guard.TextScene.TEXT_SCENE_GUILD_NAME, playerId, text);
                    if (resp == null) {
                        return com.game.match.puzzle.TacticalPuzzleService.TextVerdict.UNAVAILABLE;
                    }
                    return resp.getVerdict() == com.game.proto.content_guard.TextVerdict.TEXT_VERDICT_PASS
                            ? com.game.match.puzzle.TacticalPuzzleService.TextVerdict.PASS
                            : com.game.match.puzzle.TacticalPuzzleService.TextVerdict.REJECT;
                } catch (RuntimeException e) {
                    return com.game.match.puzzle.TacticalPuzzleService.TextVerdict.UNAVAILABLE;
                }
            };
        } else if (runMode == com.game.common.RunMode.PROD) {
            log.warn("[tactical_puzzle] prod 模式下未启用 game.match.content-guard-enabled，UGC 残局标题/提示审核将 fail-closed 拒收");
            puzzleGuard = (playerId, text) -> com.game.match.puzzle.TacticalPuzzleService.TextVerdict.UNAVAILABLE;
        } else {
            puzzleGuard = null;
        }
        GatherLauncher launcherBean = gatherLauncher.getIfAvailable();
        com.game.match.puzzle.TacticalPuzzleService.BattleLauncher puzzleLauncher = launcherBean == null
                ? null
                : (playerId, puzzleCtx, freePresetCtx, requestId) -> {
                    long bid = matchIds.nextBattleId().orElse(0L);
                    if (bid == 0L) {
                        return 0L;
                    }
                    launcherBean.launch(GatherPlan.tacticalPuzzle(
                            com.game.match.puzzle.TacticalPuzzleService.workshopDungeonId(),
                            playerId, bid, puzzleCtx, freePresetCtx));
                    return bid;
                };
        RankedTables rt = rankedTables.getIfAvailable();
        com.game.match.puzzle.TacticalPuzzleService.RankedPresetMetaProvider metaProvider = rt == null
                ? () -> new com.game.match.puzzle.TacticalPuzzleService.RankedPresetMeta(1, "")
                : () -> new com.game.match.puzzle.TacticalPuzzleService.RankedPresetMeta(
                        rt.ruleVersion(), rt.configFingerprint());
        return new com.game.match.puzzle.TacticalPuzzleService(
                store, () -> matchIds.nextBattleId().orElse(0L), puzzleGuard, puzzleLauncher, metaProvider);
    }

    @Bean
    public MatchMethodHandler listTacticalPuzzlesHandler(com.game.match.puzzle.TacticalPuzzleService service) {
        return service.clientHandlers().get(0);
    }

    @Bean
    public MatchMethodHandler getTacticalPuzzleHandler(com.game.match.puzzle.TacticalPuzzleService service) {
        return service.clientHandlers().get(1);
    }

    @Bean
    public MatchMethodHandler saveTacticalPuzzleDraftHandler(com.game.match.puzzle.TacticalPuzzleService service) {
        return service.clientHandlers().get(2);
    }

    @Bean
    public MatchMethodHandler deleteTacticalPuzzleHandler(com.game.match.puzzle.TacticalPuzzleService service) {
        return service.clientHandlers().get(3);
    }

    @Bean
    public MatchMethodHandler startTacticalPuzzleBattleHandler(com.game.match.puzzle.TacticalPuzzleService service) {
        return service.clientHandlers().get(4);
    }

    @Bean
    public MatchMethodHandler likeTacticalPuzzleHandler(com.game.match.puzzle.TacticalPuzzleService service) {
        return service.clientHandlers().get(5);
    }

    @Bean
    public MatchMethodHandler getTacticalPuzzleSolutionReplayHandler(com.game.match.puzzle.TacticalPuzzleService service) {
        return service.clientHandlers().get(6);
    }

    @Bean
    public com.game.match.spectate.SpectateChatHub spectateChatHub(
            ObjectProvider<org.redisson.api.RedissonClient> redisProvider,
            ObjectProvider<com.game.discovery.RedisKeys> redisKeysProvider) {
        org.redisson.api.RedissonClient redis = redisProvider.getIfAvailable();
        com.game.discovery.RedisKeys keys = redisKeysProvider.getIfAvailable();
        if (redis != null && keys != null) {
            return new com.game.match.spectate.SpectateChatHub(
                    com.game.match.spectate.SpectateChatHub.Config.DEFAULT, redis, keys);
        }
        return new com.game.match.spectate.SpectateChatHub(
                com.game.match.spectate.SpectateChatHub.Config.DEFAULT);
    }

    @Bean
    public com.game.match.spectate.SpectateChatService spectateChatService(
            com.game.match.placement.PlacementStore placements,
            com.game.match.spectate.SpectateChatHub hub,
            ObjectProvider<TournamentService.TextGuard> tournamentTextGuard,
            ObjectProvider<DataSource> dataSourceProvider,
            ObjectProvider<com.game.common.RunMode> runModeProvider) {
        TournamentService.TextGuard guard = tournamentTextGuard.getIfAvailable();
        com.game.common.RunMode runMode = runModeProvider.getIfAvailable(() -> com.game.common.RunMode.PROD);
        com.game.match.spectate.SpectateChatService.TextGuard chatGuard;
        if (guard != null) {
            chatGuard = guard::check;
        } else if (runMode == com.game.common.RunMode.PROD) {
            log.warn("[spectate-chat] prod 模式下未启用 game.match.content-guard-enabled，观战交流 UGC 审核将 fail-closed 拒收");
            chatGuard = (scene, playerId, text) -> {
                throw new IllegalStateException("content guard disabled in prod");
            };
        } else {
            chatGuard = null;
        }
        DataSource source = dataSourceProvider.getIfAvailable();
        java.util.function.BiFunction<Long, Deadline, String> publicNames;
        if (source != null) {
            com.game.common.player.PlayerProfiles.ConnectionSource connections =
                    source instanceof com.alibaba.druid.pool.DruidDataSource pool
                            ? pool::getConnection
                            : wait -> source.getConnection();
            var profiles = new com.game.common.player.PlayerProfiles(connections, 1);
            publicNames = (playerId, deadline) -> {
                var profile = profiles.load(List.of(playerId), deadline).get(playerId);
                return profile == null ? "" : profile.name();
            };
        } else {
            publicNames = (playerId, deadline) -> "";
        }
        return new com.game.match.spectate.SpectateChatService(
                placements, hub, chatGuard, publicNames, System::currentTimeMillis);
    }

    @Bean
    public MatchMethodHandler sendBattleSpectateChatHandler(com.game.match.spectate.SpectateChatService service) {
        return service.clientHandlers().get(0);
    }

    @Bean
    public MatchMethodHandler pullBattleSpectateChatHandler(com.game.match.spectate.SpectateChatService service) {
        return service.clientHandlers().get(1);
    }
}
