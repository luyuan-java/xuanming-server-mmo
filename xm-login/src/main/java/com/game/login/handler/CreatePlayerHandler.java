package com.game.login.handler;

import com.game.api.proto.SessionContext;
import com.game.login.character.CharacterAppearances;
import com.game.login.character.CharacterRules;
import com.game.login.character.PlayerIdGenerator;
import com.game.login.character.PlayerNames;
import com.game.login.character.RoleNameRules;
import com.game.login.dispatch.ClientMessageHandler;
import com.game.login.dispatch.HandlerReply;
import com.game.login.dispatch.InFlightKeys;
import com.game.login.dispatch.Tips;
import com.game.login.metrics.LoginMetrics;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.CreatePlayerRequest;
import com.game.proto.login.CreatePlayerResponse;
import com.game.table.LoginErrorTip;
import com.google.protobuf.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CreatePlayer（14）：在已登录账号下建一个角色，回账号下<b>全部</b>角色（新角色在末尾）。
 *
 * <p>步骤与错误码（mmorpg 登录契约 §5；纯校验都排在发号之前，名字不合规不烧号）：
 * <ol>
 *   <li>无会话 → 2018；会话未登录，或已经进了游戏（mmorpg 进游戏后清登录会话）→ 2028；</li>
 *   <li>同账号建角在途（本进程内）→ 2005；</li>
 *   <li>角色数 ≥ 上限（默认 5）→ 2001（这里先按快照快速拒绝；权威判定在第 8 步的数据库事务里）；</li>
 *   <li>class_id：0 取 Class 表第一行；不存在 → 2015。gender：0 取 1；&gt; 2 → 2015。appearance_id 不在白名单 → 2015；</li>
 *   <li>RoleNameRule 读不出或不合法 → 2020；</li>
 *   <li>名字：敏感 → 2034；不合法 → 2032（parameters = [min, max]）；为空 → 服务端生成候选名（max_generate_attempts 个）；</li>
 *   <li>发号（雪花，租约无效——丢失或续期滞后——即失败）→ 失败 2020；</li>
 *   <li>{@link PlayerStore#createPlayerWithinCap}：事务里锁账号行、重数角色（已满 → 2001）、逐个候选名插入。
 *       玩家给的名字被占：若占用者就是本账号下职业 / 性别 / 外观都相同的角色，视为上次建角应答丢失的重试，
 *       直接回当前列表；否则 2033。生成名全部撞名 → 2020。账号行不存在（数据不一致）→ 2020。</li>
 * </ol>
 * 每账号上限由第 8 步的数据库行锁保证，多个 login 实例并发建角也突破不了；本进程的在途闸门只用于快速回 2005。
 * 成功应答不设置 {@code error_message}；失败应答的 {@code players} 为空。
 */
public final class CreatePlayerHandler implements ClientMessageHandler<CreatePlayerRequest> {

    private static final Logger log = LoggerFactory.getLogger(CreatePlayerHandler.class);

    private final PlayerStore store;
    private final CharacterRules rules;
    private final PlayerIdGenerator playerIds;
    private final IntSupplier randomByte;
    private final int zoneId;
    private final int maxPlayersPerAccount;
    private final LoginMetrics metrics;
    private final InFlightKeys<String> accountsInFlight = new InFlightKeys<>();

    /**
     * @param randomByte           生成名用的随机字节源（生产为 SecureRandom），见 {@link PlayerNames#generate}
     * @param zoneId               本 login 所在 zone，写进新角色的 {@code zone_id}
     * @param maxPlayersPerAccount 每账号角色上限
     * @param metrics              新建成功的角色计数
     */
    public CreatePlayerHandler(PlayerStore store, CharacterRules rules, PlayerIdGenerator playerIds,
                               IntSupplier randomByte, int zoneId, int maxPlayersPerAccount, LoginMetrics metrics) {
        this.store = store;
        this.rules = rules;
        this.playerIds = playerIds;
        this.randomByte = randomByte;
        this.zoneId = zoneId;
        this.maxPlayersPerAccount = maxPlayersPerAccount;
        this.metrics = metrics;
    }

    @Override
    public String methodName() {
        return "CreatePlayer";
    }

    @Override
    public Class<CreatePlayerRequest> requestType() {
        return CreatePlayerRequest.class;
    }

    @Override
    public Class<CreatePlayerResponse> responseType() {
        return CreatePlayerResponse.class;
    }

    @Override
    public CompletableFuture<HandlerReply> handle(SessionContext session, CreatePlayerRequest request) {
        if (session.getSessionId() == 0) {
            return done(error(LoginErrorTip.login_error.kLoginSessionIdNotFound_VALUE));
        }
        String account = session.getAccount();
        if (account.isEmpty() || session.getPlayerId() != 0) {
            return done(error(LoginErrorTip.login_error.kLoginSessionNotFound_VALUE));
        }
        if (!accountsInFlight.tryAcquire(account)) {
            log.info("同一账号建角在途，拒绝 account={} session={}", account, session.getSessionId());
            return done(error(LoginErrorTip.login_error.kLoginInProgress_VALUE));
        }
        try {
            return done(create(account, request));
        } finally {
            accountsInFlight.release(account);
        }
    }

    private CreatePlayerResponse create(String account, CreatePlayerRequest request) {
        // 快照预检只用于快速拒绝（不持锁、不发号）；权威判定在 createPlayerWithinCap 的事务里。
        List<PlayerRow> snapshot = store.listPlayers(account);
        if (snapshot.size() >= maxPlayersPerAccount) {
            log.info("角色数已达上限 account={} count={}", account, snapshot.size());
            return error(LoginErrorTip.login_error.kLoginAccountPlayerFull_VALUE);
        }

        int classId = request.getClassId();
        int gender = request.getGender() == 0 ? 1 : request.getGender();
        String appearanceId = request.getAppearanceId();
        RoleNameRules nameRules;
        try {
            if (classId == 0) {
                classId = rules.defaultClassId();
            } else if (!rules.classExists(classId)) {
                log.info("建角拒绝：职业不存在 account={} class_id={}", account, Integer.toUnsignedLong(classId));
                return error(LoginErrorTip.login_error.kLoginUnknownError_VALUE);
            }
            if (Integer.compareUnsigned(gender, 2) > 0) {
                log.info("建角拒绝：性别非法 account={} gender={}", account, Integer.toUnsignedLong(gender));
                return error(LoginErrorTip.login_error.kLoginUnknownError_VALUE);
            }
            if (!CharacterAppearances.isAllowed(appearanceId)) {
                log.info("建角拒绝：外观不在白名单 account={} appearance_len={}", account, appearanceId.length());
                return error(LoginErrorTip.login_error.kLoginUnknownError_VALUE);
            }
            nameRules = rules.roleNameRules();
        } catch (IllegalStateException e) {
            log.error("建角拒绝：配表规则不可用 account={}", account, e);
            return error(LoginErrorTip.login_error.kLoginDataSerializeFailed_VALUE);
        }

        PlayerNames.Normalized name = PlayerNames.normalize(request.getName(), nameRules);
        switch (name.verdict()) {
            case SENSITIVE -> {
                log.info("建角拒绝：名字命中敏感词 account={} name={}", account, name.display());
                return error(LoginErrorTip.login_error.kRoleNameSensitive_VALUE);
            }
            case INVALID -> {
                // 原文没过字符集校验，可能是任意字符，只记长度。
                log.info("建角拒绝：名字不合法 account={} raw_len={}", account, request.getName().length());
                return error(LoginErrorTip.login_error.kRoleNameInvalid_VALUE,
                        Integer.toString(nameRules.minChars()), Integer.toString(nameRules.maxChars()));
            }
            case OK, EMPTY -> {
                // 往下走：OK 用玩家的名字，EMPTY 由服务端生成。
            }
        }

        boolean requested = name.verdict() == PlayerNames.Verdict.OK;
        List<String> candidates;
        if (requested) {
            candidates = List.of(name.display());
        } else {
            // 候选名在事务外先生成好：持锁期间只做 SQL，不做可能失败的配表 / 随机数逻辑。
            candidates = new ArrayList<>(nameRules.maxGenerateAttempts());
            try {
                for (int attempt = 0; attempt < nameRules.maxGenerateAttempts(); attempt++) {
                    candidates.add(PlayerNames.generate(nameRules, randomByte));
                }
            } catch (IllegalStateException e) {
                log.error("建角拒绝：生成默认名失败 account={}", account, e);
                return error(LoginErrorTip.login_error.kLoginDataSerializeFailed_VALUE);
            }
        }

        long playerId;
        try {
            playerId = playerIds.nextId();
        } catch (IllegalStateException e) {
            log.error("建角拒绝：发号失败 account={}", account, e);
            return error(LoginErrorTip.login_error.kLoginDataSerializeFailed_VALUE);
        }
        PlayerRow row = newRow(playerId, account, classId, gender, appearanceId);

        PlayerStore.CreateOutcome outcome = store.createPlayerWithinCap(row, maxPlayersPerAccount, candidates);
        return switch (outcome.status()) {
            case CREATED -> {
                metrics.playerCreated();
                yield created(account, row, outcome.existing());
            }
            case PLAYER_FULL -> {
                log.info("角色数已达上限（事务内判定）account={} count={}", account, outcome.existing().size());
                yield error(LoginErrorTip.login_error.kLoginAccountPlayerFull_VALUE);
            }
            case ACCOUNT_MISSING -> {
                log.error("建角拒绝：账号行不存在（登录时应已建好）account={}", account);
                yield error(LoginErrorTip.login_error.kLoginDataSerializeFailed_VALUE);
            }
            case NAME_TAKEN -> requested
                    ? nameTaken(account, row, outcome.existing())
                    : generatedNamesExhausted(account, candidates.size());
        };
    }

    private CreatePlayerResponse nameTaken(String account, PlayerRow row, List<PlayerRow> existing) {
        if (isLostResponseRetry(existing, row)) {
            log.info("建角重试命中已建角色（上次应答丢失）account={} name={} 本次作废 id={}",
                    account, row.getName(), row.getPlayerId());
            return success(existing);
        }
        log.info("建角拒绝：名字已被占用 account={} name={}", account, row.getName());
        return error(LoginErrorTip.login_error.kRoleNameTaken_VALUE);
    }

    private static CreatePlayerResponse generatedNamesExhausted(String account, int attempts) {
        log.error("建角拒绝：{} 个生成名全部撞名 account={}（考虑加大 RoleNameRule.generated_suffix_len）", attempts, account);
        return error(LoginErrorTip.login_error.kLoginDataSerializeFailed_VALUE);
    }

    /**
     * 名字被占，且占用者就在本账号里、展示名逐字相同、职业 / 性别 / 外观与本次请求都相同 → 上次建角其实成功、只是应答丢了。
     * 名字唯一索引建在 {@link PlayerStore#nameKey}（大小写不敏感）上，所以按唯一键找占用者：本账号里同键的那一行就是它；
     * 同键但写法不同（「Alice」占着、这次请求「alice」）不是重试，是撞名。
     */
    static boolean isLostResponseRetry(List<PlayerRow> existing, PlayerRow attempted) {
        String attemptedKey = PlayerStore.nameKey(attempted.getName());
        for (PlayerRow player : existing) {
            if (attemptedKey.equals(PlayerStore.nameKey(player.getName()))) {
                return attempted.getName().equals(player.getName())
                        && player.getClassId() == attempted.getClassId()
                        && player.getGender() == attempted.getGender()
                        && attempted.getAppearanceId().equals(player.getAppearanceId());
            }
        }
        return false;
    }

    private PlayerRow newRow(long playerId, String account, int classId, int gender, String appearanceId) {
        PlayerRow row = new PlayerRow();
        row.setPlayerId(playerId);
        row.setAccount(account);
        row.setZoneId(zoneId);
        row.setClassId(classId);
        row.setGender(gender);
        row.setAppearanceId(appearanceId);
        return row;
    }

    private static CreatePlayerResponse created(String account, PlayerRow row, List<PlayerRow> existing) {
        log.info("建角成功 account={} player_id={} name={} class_id={} gender={}",
                account, row.getPlayerId(), row.getName(), row.getClassId(), row.getGender());
        List<PlayerRow> all = new ArrayList<>(existing);
        all.add(row);
        return success(all);
    }

    private static CreatePlayerResponse success(List<PlayerRow> players) {
        return CreatePlayerResponse.newBuilder().addAllPlayers(PlayerViews.wrapAll(players)).build();
    }

    @Override
    public Optional<Message> failureBody(TipInfoMessage tip) {
        return Optional.of(CreatePlayerResponse.newBuilder().setErrorMessage(tip).build());
    }

    private static CreatePlayerResponse error(int tipId, String... parameters) {
        return CreatePlayerResponse.newBuilder().setErrorMessage(Tips.of(tipId, parameters)).build();
    }

    private static CompletableFuture<HandlerReply> done(CreatePlayerResponse response) {
        return CompletableFuture.completedFuture(HandlerReply.of(response));
    }
}
