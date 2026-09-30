package com.game.login.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.proto.SessionContext;
import com.game.common.id.Snowflake;
import com.game.login.character.CharacterRules;
import com.game.login.character.PlayerIdGenerator;
import com.game.login.character.RoleNameRules;
import com.game.login.dispatch.HandlerReply;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.player.store.PlayerStore.CreateOutcome;
import com.game.player.store.PlayerStore.CreateResult;
import com.game.player.store.PlayerStore.CreateStatus;
import com.game.proto.login.CreatePlayerRequest;
import com.game.proto.login.CreatePlayerResponse;
import com.game.table.LoginErrorTip;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CreatePlayerHandlerTest {

    private static final String ACCOUNT = "robot_0001";
    private static final int ZONE = 1;
    private static final SessionContext SESSION = SessionContext.newBuilder()
            .setGateNodeId(1).setSessionId((1 << 17) | 5).setZoneId(ZONE).setAccount(ACCOUNT).build();

    private final PlayerStore store = mock(PlayerStore.class);
    private final CharacterRules rules = mock(CharacterRules.class);
    private final AtomicBoolean leaseLost = new AtomicBoolean(false);
    private final long[] clock = {Snowflake.DEFAULT_EPOCH_MS + 5_000};
    private final PlayerIdGenerator ids = new PlayerIdGenerator(
            new Snowflake(3, Snowflake.DEFAULT_EPOCH_MS, () -> clock[0]), () -> !leaseLost.get());
    /** 每次生成名消耗 6 个字节；按调用次数轮换出不同的名字（第 n 次全是字母表第 n 个字符）。 */
    private final int[] generated = {0};
    private final IntSupplier randomByte = () -> generated[0]++ / 6;
    /** 每次事务版建角收到的候选名。 */
    private final List<List<String>> candidates = new ArrayList<>();

    private CreatePlayerHandler handler;

    @BeforeEach
    void setUp() {
        when(rules.defaultClassId()).thenReturn(1);
        when(rules.classExists(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            return id >= 1 && id <= 9;
        });
        when(rules.roleNameRules()).thenReturn(new RoleNameRules(2, 12, "道友", 6, 5));
        when(store.listPlayers(ACCOUNT)).thenReturn(List.of());
        when(store.createPlayer(any())).thenReturn(CreateResult.CREATED);
        // 事务版建角按 PlayerStore 的语义用替身组合出来（锁与事务由 PlayerStoreTest / PlayerStoreSqlTest 覆盖）：
        // 数现有角色 → 满了回 PLAYER_FULL → 逐个候选名 createPlayer。
        when(store.createPlayerWithinCap(any(), anyInt(), anyList())).thenAnswer(inv -> {
            PlayerRow row = inv.getArgument(0);
            int cap = inv.getArgument(1);
            List<String> names = inv.getArgument(2);
            candidates.add(List.copyOf(names));
            List<PlayerRow> existing = store.listPlayers(row.getAccount());
            if (existing.size() >= cap) {
                return new CreateOutcome(CreateStatus.PLAYER_FULL, existing);
            }
            for (String name : names) {
                row.setName(name);
                if (store.createPlayer(row) == CreateResult.CREATED) {
                    return new CreateOutcome(CreateStatus.CREATED, existing);
                }
            }
            return new CreateOutcome(CreateStatus.NAME_TAKEN, existing);
        });
        handler = new CreatePlayerHandler(store, rules, ids, randomByte, ZONE, 5);
    }

    private static CreatePlayerResponse response(HandlerReply reply) throws Exception {
        assertThat(reply.body()).isPresent();
        assertThat(reply.directives()).isEmpty();
        return CreatePlayerResponse.parseFrom(reply.body().get().toByteString());
    }

    private CreatePlayerResponse create(SessionContext session, CreatePlayerRequest request) throws Exception {
        return response(handler.handle(session, request).join());
    }

    private CreatePlayerResponse create(CreatePlayerRequest request) throws Exception {
        return create(SESSION, request);
    }

    private static void assertError(CreatePlayerResponse response, int tipId) {
        assertThat(response.hasErrorMessage()).isTrue();
        assertThat(response.getErrorMessage().getId()).isEqualTo(tipId);
        assertThat(response.getPlayersList()).isEmpty();
    }

    @Test
    void 空请求用默认值建角_新角色在末尾() throws Exception {
        PlayerRow old = LoginHandlerTest.row(100, ACCOUNT, "张三");
        when(store.listPlayers(ACCOUNT)).thenReturn(List.of(old));

        CreatePlayerResponse response = create(CreatePlayerRequest.getDefaultInstance());

        assertThat(response.hasErrorMessage()).isFalse();
        ArgumentCaptor<PlayerRow> inserted = ArgumentCaptor.forClass(PlayerRow.class);
        verify(store).createPlayer(inserted.capture());
        PlayerRow row = inserted.getValue();
        assertThat(row.getAccount()).isEqualTo(ACCOUNT);
        assertThat(row.getClassId()).isEqualTo(1);
        assertThat(row.getGender()).isEqualTo(1);
        assertThat(row.getAppearanceId()).isEmpty();
        assertThat(row.getZoneId()).isEqualTo(ZONE);
        assertThat(row.getName()).isEqualTo("道友aaaaaa");
        assertThat(Snowflake.workerOf(row.getPlayerId())).isEqualTo(3);

        assertThat(response.getPlayersList()).extracting(w -> w.getPlayer().getPlayerId())
                .containsExactly(100L, row.getPlayerId());
        var created = response.getPlayers(1).getPlayer();
        assertThat(created.getName()).isEqualTo("道友aaaaaa");
        assertThat(created.getClassId()).isEqualTo(1);
        assertThat(created.getGender()).isEqualTo(1);
        assertThat(created.getZoneId()).isEqualTo(ZONE);
        assertThat(created.getAppearanceId()).isEmpty();
    }

    @Test
    void 玩家给的名字归一化后入库() throws Exception {
        CreatePlayerResponse response = create(CreatePlayerRequest.newBuilder()
                .setName(" ＡＢＣ ").setClassId(3).setGender(2).setAppearanceId("01_ice_sword_girl").build());

        assertThat(response.hasErrorMessage()).isFalse();
        var created = response.getPlayers(0).getPlayer();
        assertThat(created.getName()).isEqualTo("ABC");
        assertThat(created.getClassId()).isEqualTo(3);
        assertThat(created.getGender()).isEqualTo(2);
        assertThat(created.getAppearanceId()).isEqualTo("01_ice_sword_girl");
    }

    @Test
    void 会话前置条件() throws Exception {
        assertError(create(SESSION.toBuilder().setSessionId(0).build(), CreatePlayerRequest.getDefaultInstance()),
                LoginErrorTip.login_error.kLoginSessionIdNotFound_VALUE);
        assertError(create(SESSION.toBuilder().clearAccount().build(), CreatePlayerRequest.getDefaultInstance()),
                LoginErrorTip.login_error.kLoginSessionNotFound_VALUE);
        // 已进游戏的会话（mmorpg 进游戏后清登录会话）。
        assertError(create(SESSION.toBuilder().setPlayerId(9).build(), CreatePlayerRequest.getDefaultInstance()),
                LoginErrorTip.login_error.kLoginSessionNotFound_VALUE);
        verify(store, never()).createPlayer(any());
    }

    @Test
    void 角色数达上限回2001() throws Exception {
        List<PlayerRow> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            five.add(LoginHandlerTest.row(i + 1, ACCOUNT, "角色" + i));
        }
        when(store.listPlayers(ACCOUNT)).thenReturn(five);

        assertError(create(CreatePlayerRequest.getDefaultInstance()),
                LoginErrorTip.login_error.kLoginAccountPlayerFull_VALUE);
        verify(store, never()).createPlayer(any());
    }

    @Test
    void 职业性别外观非法回2015() throws Exception {
        int unknown = LoginErrorTip.login_error.kLoginUnknownError_VALUE;
        assertError(create(CreatePlayerRequest.newBuilder().setClassId(10).build()), unknown);
        assertError(create(CreatePlayerRequest.newBuilder().setGender(3).build()), unknown);
        // uint32 超大值在 Java 里是负数。
        assertError(create(CreatePlayerRequest.newBuilder().setGender(-1).build()), unknown);
        assertError(create(CreatePlayerRequest.newBuilder().setClassId(-1).build()), unknown);
        assertError(create(CreatePlayerRequest.newBuilder().setAppearanceId("99_unknown").build()), unknown);
        verify(store, never()).createPlayer(any());
    }

    @Test
    void 配表规则不可用回2020() throws Exception {
        when(rules.roleNameRules()).thenThrow(new IllegalStateException("缺行"));
        assertError(create(CreatePlayerRequest.getDefaultInstance()),
                LoginErrorTip.login_error.kLoginDataSerializeFailed_VALUE);

        when(rules.defaultClassId()).thenThrow(new IllegalStateException("Class 表为空"));
        assertError(create(CreatePlayerRequest.getDefaultInstance()),
                LoginErrorTip.login_error.kLoginDataSerializeFailed_VALUE);
        verify(store, never()).createPlayer(any());
    }

    @Test
    void 名字不合法回2032并带长度参数() throws Exception {
        CreatePlayerResponse response = create(CreatePlayerRequest.newBuilder().setName("a").build());
        assertError(response, LoginErrorTip.login_error.kRoleNameInvalid_VALUE);
        assertThat(response.getErrorMessage().getParametersList()).containsExactly("2", "12");
    }

    @Test
    void 名字敏感回2034() throws Exception {
        assertError(create(CreatePlayerRequest.newBuilder().setName("官方客服").build()),
                LoginErrorTip.login_error.kRoleNameSensitive_VALUE);
        verify(store, never()).createPlayer(any());
    }

    @Test
    void 名字被别人占用回2033() throws Exception {
        when(store.createPlayer(any())).thenReturn(CreateResult.NAME_TAKEN);
        assertError(create(CreatePlayerRequest.newBuilder().setName("张三").build()),
                LoginErrorTip.login_error.kRoleNameTaken_VALUE);
    }

    @Test
    void 名字被本账号同参数角色占用视为丢应答重试() throws Exception {
        PlayerRow mine = LoginHandlerTest.row(100, ACCOUNT, "张三");
        when(store.listPlayers(ACCOUNT)).thenReturn(List.of(mine));
        when(store.createPlayer(any())).thenReturn(CreateResult.NAME_TAKEN);

        CreatePlayerResponse response = create(CreatePlayerRequest.newBuilder().setName("张三").build());

        assertThat(response.hasErrorMessage()).isFalse();
        assertThat(response.getPlayersList()).extracting(w -> w.getPlayer().getPlayerId()).containsExactly(100L);
    }

    @Test
    void 名字被本账号同键不同写法的角色占用_不是重试回2033() throws Exception {
        // 唯一键大小写不敏感：「Alice」占着，请求「alice」撞的是同一个键，但重试一定逐字相同，所以这是撞名。
        PlayerRow mine = LoginHandlerTest.row(100, ACCOUNT, "Alice");
        when(store.listPlayers(ACCOUNT)).thenReturn(List.of(mine));
        when(store.createPlayer(any())).thenReturn(CreateResult.NAME_TAKEN);

        assertError(create(CreatePlayerRequest.newBuilder().setName("alice").build()),
                LoginErrorTip.login_error.kRoleNameTaken_VALUE);
    }

    @Test
    void 名字被本账号不同参数角色占用仍回2033() throws Exception {
        PlayerRow mine = LoginHandlerTest.row(100, ACCOUNT, "张三");
        mine.setClassId(2);
        when(store.listPlayers(ACCOUNT)).thenReturn(List.of(mine));
        when(store.createPlayer(any())).thenReturn(CreateResult.NAME_TAKEN);

        assertError(create(CreatePlayerRequest.newBuilder().setName("张三").build()),
                LoginErrorTip.login_error.kRoleNameTaken_VALUE);
    }

    @Test
    void 生成名撞名换一个() throws Exception {
        List<String> tried = new ArrayList<>();
        when(store.createPlayer(any())).thenAnswer(inv -> {
            tried.add(inv.<PlayerRow>getArgument(0).getName());
            return tried.size() == 1 ? CreateResult.NAME_TAKEN : CreateResult.CREATED;
        });

        CreatePlayerResponse response = create(CreatePlayerRequest.getDefaultInstance());

        assertThat(response.hasErrorMessage()).isFalse();
        assertThat(tried).containsExactly("道友aaaaaa", "道友bbbbbb");
        assertThat(response.getPlayers(0).getPlayer().getName()).isEqualTo("道友bbbbbb");
    }

    @Test
    void 生成名全部撞名回2020() throws Exception {
        when(store.createPlayer(any())).thenReturn(CreateResult.NAME_TAKEN);
        assertError(create(CreatePlayerRequest.getDefaultInstance()),
                LoginErrorTip.login_error.kLoginDataSerializeFailed_VALUE);
        verify(store, times(5)).createPlayer(any());
    }

    @Test
    void 租约丢失不发号回2020() throws Exception {
        leaseLost.set(true);
        assertError(create(CreatePlayerRequest.getDefaultInstance()),
                LoginErrorTip.login_error.kLoginDataSerializeFailed_VALUE);
        verify(store, never()).createPlayer(any());
    }

    @Test
    void 上限由事务内的判定为准_预检通过但事务内已满回2001() throws Exception {
        // 另一个 login 实例在本次预检之后建了第 5 个：事务里锁账号行后重数，回 PLAYER_FULL。
        doReturn(new CreateOutcome(CreateStatus.PLAYER_FULL, List.of()))
                .when(store).createPlayerWithinCap(any(), anyInt(), anyList());

        assertError(create(CreatePlayerRequest.getDefaultInstance()),
                LoginErrorTip.login_error.kLoginAccountPlayerFull_VALUE);
    }

    @Test
    void 账号行不存在_fail_closed回2020() throws Exception {
        doReturn(new CreateOutcome(CreateStatus.ACCOUNT_MISSING, List.of()))
                .when(store).createPlayerWithinCap(any(), anyInt(), anyList());

        assertError(create(CreatePlayerRequest.newBuilder().setName("张三").build()),
                LoginErrorTip.login_error.kLoginDataSerializeFailed_VALUE);
    }

    @Test
    void 上限与候选名交给事务版建角_玩家给的名字只有一个候选_生成名一次给足() throws Exception {
        create(CreatePlayerRequest.newBuilder().setName("张三").build());
        create(CreatePlayerRequest.getDefaultInstance());

        ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
        verify(store, times(2)).createPlayerWithinCap(any(), cap.capture(), anyList());
        assertThat(cap.getAllValues()).containsOnly(5);
        assertThat(candidates.get(0)).containsExactly("张三");
        assertThat(candidates.get(1)).hasSize(5).doesNotHaveDuplicates();
    }

    @Test
    void 同账号建角在途时后到者回2005() throws Exception {
        AtomicReference<HandlerReply> nested = new AtomicReference<>();
        doAnswer(inv -> {
            nested.set(handler.handle(SESSION, CreatePlayerRequest.getDefaultInstance()).join());
            return List.of();
        }).when(store).listPlayers(ACCOUNT);

        assertThat(create(CreatePlayerRequest.getDefaultInstance()).hasErrorMessage()).isFalse();
        assertError(response(nested.get()), LoginErrorTip.login_error.kLoginInProgress_VALUE);
    }
}
