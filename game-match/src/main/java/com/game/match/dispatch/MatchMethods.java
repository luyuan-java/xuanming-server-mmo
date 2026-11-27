package com.game.match.dispatch;

import java.util.List;

/**
 * 契约里 {@code match.MatchService} 的服务名与 10 个方法名（{@code proto/match/match_service.proto:21-46}）。消息号一律经
 * {@code MessageIdRegistry.requireId(SERVICE, 方法名)} 取，不写死数字（号表随契约同步，Java 没有生成的消息号常量）；现在的号见各常量的注释。
 * 处理器的 {@link MatchMethodHandler#method()} 与指标的 {@code method} 标签都用这里的常量。
 */
public final class MatchMethods {

    /** 服务裸名（与 {@code message_id.txt} 的键前缀一致）。 */
    public static final String SERVICE = "MatchService";

    /** 157：排队。 */
    public static final String JOIN_QUEUE = "JoinQueue";
    /** 148：取消排队（应答 Empty：成功不回包）。 */
    public static final String CANCEL_QUEUE = "CancelQueue";
    /** 153：查排队状态。 */
    public static final String GET_QUEUE_STATUS = "GetQueueStatus";
    /** 152：发起切磋。 */
    public static final String CHALLENGE_PLAYER = "ChallengePlayer";
    /** 151：应答切磋。 */
    public static final String RESPOND_CHALLENGE = "RespondChallenge";
    /** 156：切磋邀请推送的号；作为上行是空操作（回 Empty）。 */
    public static final String NOTIFY_CHALLENGE_INVITE = "NotifyChallengeInvite";
    /** 154：切磋结果推送的号；作为上行是空操作（回 Empty）。 */
    public static final String NOTIFY_CHALLENGE_RESULT = "NotifyChallengeResult";
    /** 163：观战（批次 6.5，spectate-spec §3.1；处理器在 spectate 包，跑在它自己的执行器上）。 */
    public static final String WATCH_BATTLE = "WatchBattle";
    /** 164：可观战列表（批次 6.5，spectate-spec §3.3；处理器在 spectate 包，跑在 match-worker 上）。 */
    public static final String LIST_WATCHABLE_BATTLES = "ListWatchableBattles";
    /** 179：战斗票据补签（请求 / 应答消息在 battle 包）。 */
    public static final String REQUEST_BATTLE_TICKET = "RequestBattleTicket";
    public static final String GET_NETWORK_PROBE_PLAN = "GetNetworkProbePlan";
    public static final String COMMIT_NETWORK_PROBE = "CommitNetworkProbe";
    /** 379/380：战报列表与回放正文查询。 */
    public static final String LIST_BATTLE_REPORTS = "ListBattleReports";
    public static final String GET_BATTLE_REPORT = "GetBattleReport";
    /** 389–392：战斗剧本列表、提交、删除、选用。 */
    public static final String LIST_BATTLE_SCRIPTS = "ListBattleScripts";
    public static final String SUBMIT_BATTLE_SCRIPT = "SubmitBattleScript";
    public static final String DELETE_BATTLE_SCRIPT = "DeleteBattleScript";
    public static final String SET_BATTLE_SCRIPT_LOADOUT = "SetBattleScriptLoadout";
    /** 448–451：论剑排位资料、默认预设、饰品佩戴与战果查询。 */
    public static final String GET_RANKED_PROFILE = "GetRankedProfile";
    public static final String SAVE_RANKED_PRESET = "SaveRankedPreset";
    public static final String UPDATE_RANKED_COSMETIC = "UpdateRankedCosmetic";
    public static final String GET_RANKED_BATTLE_RESULT = "GetRankedBattleResult";
    /** 465–475：全服赛季战队锦标赛《群仙问道赛》V1 客户端 11 条协议。 */
    public static final String GET_TOURNAMENT_OVERVIEW = "GetTournamentOverview";
    public static final String CREATE_TOURNAMENT_SQUAD = "CreateTournamentSquad";
    public static final String INVITE_TOURNAMENT_MEMBER = "InviteTournamentMember";
    public static final String RESPOND_TOURNAMENT_INVITE = "RespondTournamentInvite";
    public static final String LEAVE_TOURNAMENT_SQUAD = "LeaveTournamentSquad";
    public static final String UPDATE_TOURNAMENT_LINEUP = "UpdateTournamentLineup";
    public static final String REGISTER_TOURNAMENT_SQUAD = "RegisterTournamentSquad";
    public static final String LIST_TOURNAMENT_STANDINGS = "ListTournamentStandings";
    public static final String START_TOURNAMENT_MATCH = "StartTournamentMatch";
    public static final String CHEER_TOURNAMENT_SQUAD = "CheerTournamentSquad";
    public static final String CLAIM_TOURNAMENT_REWARD = "ClaimTournamentReward";
    /** 507–508：全服赛季战队锦标赛《群仙问道赛》V2 观战预设喝彩（原 506 每日观道致敬已移除并永久保留号段）。 */
    public static final String SEND_TOURNAMENT_SPECTATE_CHEER = "SendTournamentSpectateCheer";
    public static final String GET_TOURNAMENT_SPECTATE_CHEER = "GetTournamentSpectateCheer";
    /** 全服赛季战队锦标赛《群仙问道赛》V3 队徽更换、零积分热身约战与神机免费竞猜。 */
    public static final String UPDATE_TOURNAMENT_SQUAD_EMBLEM = "UpdateTournamentSquadEmblem";
    public static final String START_TOURNAMENT_WARMUP = "StartTournamentWarmup";
    public static final String PREDICT_TOURNAMENT_MATCH = "PredictTournamentMatch";
    public static final String GET_TOURNAMENT_PREDICTION_BOARD = "GetTournamentPredictionBoard";
    /** 全服赛季战队锦标赛《群仙问道赛》V4 BO3灵脉禁选与本季名局录。 */
    public static final String BAN_TOURNAMENT_LEYLINE = "BanTournamentLeyline";
    public static final String GET_TOURNAMENT_HIGHLIGHTS = "GetTournamentHighlights";
    /** 江湖恩怨与天墉生死状一期(548..554)。 */
    public static final String CREATE_JIANGHU_DUEL_INVITE = "CreateJianghuDuelInvite";
    public static final String CONFIRM_JIANGHU_DUEL_MEMBER = "ConfirmJianghuDuelMember";
    public static final String RESPOND_JIANGHU_DUEL_INVITE = "RespondJianghuDuelInvite";
    public static final String LIST_JIANGHU_GRUDGES = "ListJianghuGrudges";
    public static final String DISMISS_JIANGHU_GRUDGE = "DismissJianghuGrudge";
    public static final String NOTIFY_JIANGHU_DUEL_INVITE = "NotifyJianghuDuelInvite";
    public static final String NOTIFY_JIANGHU_DUEL_STATUS = "NotifyJianghuDuelStatus";
    /** 五行试炼志 V3 好友约战帖与受控 PVE 录像播放(570..572)。 */
    public static final String CREATE_TRIAL_CHALLENGE_CARD = "CreateTrialChallengeCard";
    public static final String GET_TRIAL_CHALLENGE_CARD = "GetTrialChallengeCard";
    public static final String GET_SHARED_TRIAL_REPLAY = "GetSharedTrialReplay";
    /** 万象残局坊 V1（575..581）。 */
    public static final String LIST_TACTICAL_PUZZLES = "ListTacticalPuzzles";
    public static final String GET_TACTICAL_PUZZLE = "GetTacticalPuzzle";
    public static final String SAVE_TACTICAL_PUZZLE_DRAFT = "SaveTacticalPuzzleDraft";
    public static final String DELETE_TACTICAL_PUZZLE = "DeleteTacticalPuzzle";
    public static final String START_TACTICAL_PUZZLE_BATTLE = "StartTacticalPuzzleBattle";
    public static final String LIKE_TACTICAL_PUZZLE = "LikeTacticalPuzzle";
    public static final String GET_TACTICAL_PUZZLE_SOLUTION_REPLAY = "GetTacticalPuzzleSolutionReplay";
    /** 局外人观战公开交流室（587..588）。 */
    public static final String SEND_BATTLE_SPECTATE_CHAT = "SendBattleSpectateChat";
    public static final String PULL_BATTLE_SPECTATE_CHAT = "PullBattleSpectateChat";

    /** 全部 60 个客户端方法（顺序无意义）。契约里的 {@code MatchService} 多出或少了方法时，{@code MatchMethodsTest} 会失败。 */
    public static final List<String> ALL = List.of(JOIN_QUEUE, CANCEL_QUEUE, GET_QUEUE_STATUS, CHALLENGE_PLAYER, RESPOND_CHALLENGE,
            NOTIFY_CHALLENGE_INVITE, NOTIFY_CHALLENGE_RESULT, WATCH_BATTLE, LIST_WATCHABLE_BATTLES, REQUEST_BATTLE_TICKET,
            GET_NETWORK_PROBE_PLAN, COMMIT_NETWORK_PROBE,
            LIST_BATTLE_REPORTS, GET_BATTLE_REPORT,
            LIST_BATTLE_SCRIPTS, SUBMIT_BATTLE_SCRIPT, DELETE_BATTLE_SCRIPT, SET_BATTLE_SCRIPT_LOADOUT,
            GET_RANKED_PROFILE, SAVE_RANKED_PRESET, UPDATE_RANKED_COSMETIC, GET_RANKED_BATTLE_RESULT,
            GET_TOURNAMENT_OVERVIEW, CREATE_TOURNAMENT_SQUAD, INVITE_TOURNAMENT_MEMBER, RESPOND_TOURNAMENT_INVITE,
            LEAVE_TOURNAMENT_SQUAD, UPDATE_TOURNAMENT_LINEUP, REGISTER_TOURNAMENT_SQUAD, LIST_TOURNAMENT_STANDINGS,
            START_TOURNAMENT_MATCH, CHEER_TOURNAMENT_SQUAD, CLAIM_TOURNAMENT_REWARD,
            SEND_TOURNAMENT_SPECTATE_CHEER, GET_TOURNAMENT_SPECTATE_CHEER,
            UPDATE_TOURNAMENT_SQUAD_EMBLEM, START_TOURNAMENT_WARMUP, PREDICT_TOURNAMENT_MATCH, GET_TOURNAMENT_PREDICTION_BOARD,
            BAN_TOURNAMENT_LEYLINE, GET_TOURNAMENT_HIGHLIGHTS,
            CREATE_JIANGHU_DUEL_INVITE, CONFIRM_JIANGHU_DUEL_MEMBER, RESPOND_JIANGHU_DUEL_INVITE,
            LIST_JIANGHU_GRUDGES, DISMISS_JIANGHU_GRUDGE, NOTIFY_JIANGHU_DUEL_INVITE, NOTIFY_JIANGHU_DUEL_STATUS,
            CREATE_TRIAL_CHALLENGE_CARD, GET_TRIAL_CHALLENGE_CARD, GET_SHARED_TRIAL_REPLAY,
            LIST_TACTICAL_PUZZLES, GET_TACTICAL_PUZZLE, SAVE_TACTICAL_PUZZLE_DRAFT, DELETE_TACTICAL_PUZZLE,
            START_TACTICAL_PUZZLE_BATTLE, LIKE_TACTICAL_PUZZLE, GET_TACTICAL_PUZZLE_SOLUTION_REPLAY,
            SEND_BATTLE_SPECTATE_CHAT, PULL_BATTLE_SPECTATE_CHAT);

    private MatchMethods() {
    }
}
