package com.game.match.feature;

import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.id.MatchIds;
import com.game.match.ranked.RankedService;
import com.game.match.ranked.RankedStore;
import com.game.match.ranked.RankedTables;
import com.game.match.rating.MatchRatingTables;
import com.game.match.rating.RatingConfiguration;
import com.game.match.report.BattleReportHandlers;
import com.game.match.report.BattleReportStore;
import com.game.match.script.BattleScriptCatalog;
import com.game.match.script.BattleScriptResolver;
import com.game.match.script.BattleScriptService;
import com.game.match.script.BattleScriptStore;
import com.game.match.script.BattleScriptValidator;
import com.game.match.tournament.TournamentService;
import com.game.match.tournament.TournamentStore;
import com.game.match.tournament.TournamentTables;
import com.game.table.ConfigTables;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
    public BattleReportStore battleReportStore(DataSource dataSource, FeatureSchemaReady schemaReady) {
        return new BattleReportStore(dataSource);
    }

    @Bean
    public MatchMethodHandler listBattleReportsHandler(BattleReportStore store) {
        return BattleReportHandlers.listHandler(store);
    }

    @Bean
    public MatchMethodHandler getBattleReportHandler(BattleReportStore store) {
        return BattleReportHandlers.getHandler(store);
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

    @Bean
    public TournamentTables tournamentTables(ConfigTables config) {
        return TournamentTables.from(config);
    }

    @Bean
    public TournamentStore tournamentStore(DataSource dataSource, TournamentTables tables, FeatureSchemaReady schemaReady) {
        return new TournamentStore(dataSource, tables, System::currentTimeMillis);
    }

    @Bean
    public TournamentService tournamentService(TournamentStore store, MatchIds matchIds,
                                               ObjectProvider<TournamentService.MatchLauncher> launcher) {
        return new TournamentService(store, () -> matchIds.nextBattleId().orElse(0L), launcher.getIfAvailable());
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
}
