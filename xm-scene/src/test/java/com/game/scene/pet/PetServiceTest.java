package com.game.scene.pet;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.PetEntry;
import com.game.player.store.state.PetState;
import com.game.player.store.state.PlayerState;
import com.game.proto.PetDimensionInfo;
import com.game.proto.PetInfo;
import com.game.proto.PetListInfo;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.currency.CurrencyService;
import com.game.scene.player.PlayerPets;
import com.game.scene.player.PlayerPets.Pet;
import com.game.scene.player.Wallet;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingAssetAudit;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.WorldTestAccess;
import com.game.table.ConfigTables;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 宝宝服务（基线 PetSystem），配表用正式表（宝宝池 4：维度 401 体质 / 402 灵力 / 403 力量 / 404 敏捷，每级 5 点，
 * 30 级起洗点 300 金币；改名 200 金币；可携带 10 只）。资质随机数固定取区间下限：灵狐（种类 1）= 8000 / 10000 / 7000 / 11000。
 */
class PetServiceTest {

    private static final long PLAYER = 1001;
    private static PetTables tables;

    private final RecordingAssetAudit audit = new RecordingAssetAudit();
    private final ManualClock clock = new ManualClock();
    private final AtomicLong guidSeq = new AtomicLong(1L << 60);
    /** 还能发的号数（铸不出返回 null）。 */
    private long guidBudget = Long.MAX_VALUE;
    private PetService service;
    private ScenePlayer player;

    /** 闭区间随机恒取下限。 */
    private static final RandomGenerator LOWEST = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public long nextLong(long origin, long bound) {
            return origin;
        }
    };

    @BeforeAll
    static void loadTables() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables")
                : Path.of("config-data/tables");
        tables = PetTables.from(ConfigTables.load(dir));
    }

    @BeforeEach
    void setUp() {
        service = new PetService(tables, new CurrencyService(audit), count -> {
            if (count > guidBudget) {
                return null;
            }
            guidBudget -= count;
            long[] out = new long[count];
            for (int i = 0; i < count; i++) {
                out[i] = guidSeq.incrementAndGet();
            }
            return out;
        }, clock, LOWEST);
        player = WorldTestAccess.player(PLAYER);
        service.initializeOnLoad(player);
    }

    private long grant(int petTableId) {
        PetService.Grant grant = service.grant(player, petTableId);
        assertThat(grant.tip()).isZero();
        return grant.petId();
    }

    private PetInfo info(long petId) {
        return service.buildList(player).getPetsList().stream().filter(p -> p.getPetId() == petId).findFirst()
                .orElseThrow();
    }

    private void setLevel(int level) {
        player.setLevel(level);
        service.onOwnerLevelChanged(player);
    }

    // ------------------------------------------------------------------ 发放与列表

    @Test
    void 新号列表为空_带可携带上限与改名金币() {
        PetListInfo list = service.buildList(player);
        assertThat(list.getPetsList()).isEmpty();
        assertThat(list.getActivePetId()).isZero();
        assertThat(list.getMaxPets()).isEqualTo(10);
        assertThat(list.getRenameCostGold()).isEqualTo(200);
    }

    @Test
    void GM发放_铸号掷资质按主人等级定级_满血满蓝出生_列表全量() {
        long petId = grant(1);

        PetInfo pet = info(petId);
        assertThat(pet.getPetTableId()).isEqualTo(1);
        assertThat(pet.getName()).isEqualTo("灵狐");
        assertThat(pet.getLevel()).isEqualTo(1);
        assertThat(pet.getIsActive()).isFalse();
        assertThat(pet.getPoolId()).isEqualTo(4);
        assertThat(pet.getTotalPoints()).isEqualTo(5);
        assertThat(pet.getRemainingPoints()).isEqualTo(5);
        assertThat(pet.getResetCostGold()).as("30 级以下免费").isZero();
        assertThat(pet.getGrowth()).isEqualTo(9000);
        assertThat(pet.getDimensionsList()).extracting(PetDimensionInfo::getDimensionId).containsExactly(401, 402, 403, 404);
        assertThat(pet.getDimensionsList()).extracting(PetDimensionInfo::getAptitude).containsExactly(8000, 10000, 7000, 11000);
        assertThat(pet.getDimensionsList()).extracting(PetDimensionInfo::getValue).containsOnly(1L);
        assertThat(pet.getDimensionsList()).extracting(PetDimensionInfo::getName).containsExactly("体质", "灵力", "力量", "敏捷");
        assertThat(pet.getDerived().getMaxHealth()).as("400 + 42 × 0.8").isEqualTo(433);
        assertThat(pet.getDerived().getMaxMana()).as("800 + 30 × 1.0").isEqualTo(830);
        assertThat(pet.getDerived().getPhysicalAttack()).as("40 × 0.7").isEqualTo(28);
        assertThat(pet.getDerived().getMagicAttack()).isEqualTo(33);
        assertThat(pet.getDerived().getSpeed()).as("144 + 30 × 1.1").isEqualTo(177);
        assertThat(pet.getDerived().getDefense()).as("45 × 0.8").isEqualTo(36);
        assertThat(pet.getDerived().getHealth()).isEqualTo(433);
        assertThat(pet.getDerived().getMana()).isEqualTo(830);
        assertThat(player.pets().find(petId).createdAt()).isEqualTo(ManualClock.EPOCH_MILLIS_START / 1000);
    }

    @Test
    void 发放拒绝码_种类缺失26001_槽位满26002_号源不可用26016且不留半只() {
        assertThat(service.grant(player, 99).tip()).isEqualTo(26001);
        for (int i = 0; i < 10; i++) {
            grant(1);
        }
        assertThat(service.grant(player, 1).tip()).isEqualTo(26002);

        player = WorldTestAccess.player(PLAYER + 1);
        guidBudget = 0;
        assertThat(service.grant(player, 1).tip()).isEqualTo(26016);
        assertThat(player.pets().size()).isZero();
    }

    // ------------------------------------------------------------------ 出战

    @Test
    void 出战与收回_同时只能一只_重复召唤26004_主人等级不够26003_没有出战再收回26005() {
        long fox = grant(1);
        long lion = grant(3);
        assertThat(service.summon(player, 12345)).isEqualTo(26000);
        assertThat(service.summon(player, lion)).as("金猊要主人 10 级").isEqualTo(26003);
        assertThat(service.summon(player, fox)).isZero();
        assertThat(service.summon(player, fox)).isEqualTo(26004);
        setLevel(10);
        assertThat(service.summon(player, lion)).as("换一只出战").isZero();
        assertThat(service.buildList(player).getActivePetId()).isEqualTo(lion);
        assertThat(service.recall(player)).isZero();
        assertThat(service.recall(player)).isEqualTo(26005);
    }

    // ------------------------------------------------------------------ 加点 / 洗点 / 自动加点

    @Test
    void 自动加点只算不落_按方案权重分_余数按优先序补() {
        long petId = grant(1);

        PetService.Suggestion suggestion = service.autoAllocate(player, petId);

        assertThat(suggestion.tip()).isZero();
        assertThat(suggestion.suggested()).containsExactly(Map.entry(401, 2L), Map.entry(402, 0L), Map.entry(403, 3L),
                Map.entry(404, 0L));
        assertThat(info(petId).getRemainingPoints()).as("只算不落").isEqualTo(5);
        assertThat(service.autoAllocate(player, 777).tip()).isEqualTo(26000);

        setLevel(30);
        assertThat(service.autoAllocate(player, petId).suggested()).containsExactly(Map.entry(401, 50L),
                Map.entry(402, 0L), Map.entry(403, 75L), Map.entry(404, 25L));
    }

    @Test
    void 加点按建议提交_剩余归零二级属性变大_幂等26015_减点26010_超剩余26009_维度不在池26012() {
        long petId = grant(1);
        long healthBefore = info(petId).getDerived().getMaxHealth();

        assertThat(service.allocate(player, petId, Map.of(401, 2L, 403, 3L))).isZero();

        PetInfo pet = info(petId);
        assertThat(pet.getRemainingPoints()).isZero();
        assertThat(pet.getDerived().getMaxHealth()).isGreaterThan(healthBefore);
        assertThat(pet.getDerived().getHealth()).as("满血按比例保持满").isEqualTo(pet.getDerived().getMaxHealth());
        assertThat(service.allocate(player, petId, Map.of(401, 2L, 403, 3L))).isEqualTo(26015);
        assertThat(service.allocate(player, petId, Map.of(401, 1L))).isEqualTo(26010);
        setLevel(2);
        assertThat(service.allocate(player, petId, Map.of(404, 6L))).isEqualTo(26009);
        assertThat(service.allocate(player, petId, Map.of(999, 1L))).isEqualTo(26012);
        assertThat(service.allocate(player, 777, Map.of(401, 3L))).isEqualTo(26000);
    }

    @Test
    void 洗点_没分配过26015_30级以下免费_30级起扣300金币_余额不足26013不动() {
        long petId = grant(1);
        assertThat(service.reset(player, petId)).isEqualTo(26015);
        assertThat(service.allocate(player, petId, Map.of(403, 5L))).isZero();
        assertThat(service.reset(player, petId)).isZero();
        assertThat(info(petId).getRemainingPoints()).isEqualTo(5);
        assertThat(audit.currencies).isEmpty();

        setLevel(30);
        assertThat(service.allocate(player, petId, Map.of(403, 150L))).isZero();
        assertThat(service.reset(player, petId)).isEqualTo(26013);
        assertThat(info(petId).getRemainingPoints()).isZero();

        player.wallet().add(Wallet.GOLD, 1000);
        assertThat(service.reset(player, petId)).isZero();
        assertThat(info(petId).getRemainingPoints()).isEqualTo(150);
        assertThat(player.wallet().balance(Wallet.GOLD)).isEqualTo(700);
        assertThat(audit.currencies).extracting(RecordingAssetAudit.Currency::reason).containsExactly(Reason.PET_RESET);
    }

    @Test
    void 加点洗点按比例保持当前气血_往返不能回血() {
        long petId = grant(1);
        setLevel(30);
        assertThat(service.allocate(player, petId, Map.of(401, 150L))).isZero();
        Pet pet = player.pets().find(petId);
        long max = info(petId).getDerived().getMaxHealth();
        pet.setHealth(max / 2);
        player.wallet().add(Wallet.GOLD, 1000);

        assertThat(service.reset(player, petId)).isZero();
        long afterReset = pet.health();
        assertThat(afterReset).isLessThan(max / 2);
        assertThat(service.allocate(player, petId, Map.of(401, 150L))).isZero();
        assertThat(pet.health()).isLessThanOrEqualTo(max / 2);
    }

    // ------------------------------------------------------------------ 改名

    @Test
    void 改名_名字非法26007先于找宝宝_同名不收钱26015_扣200金币() {
        long petId = grant(1);
        assertThat(service.rename(player, 777, "   ")).isEqualTo(26007);
        assertThat(service.rename(player, petId, "一二三四五六七八九")).as("超 8 个码点").isEqualTo(26007);
        assertThat(service.rename(player, petId, "灵狐")).as("与种类名相同").isEqualTo(26015);
        assertThat(service.rename(player, 777, "小白")).isEqualTo(26000);
        assertThat(service.rename(player, petId, "小白")).isEqualTo(26013);
        player.wallet().add(Wallet.GOLD, 200);
        assertThat(service.rename(player, petId, "小白")).isZero();
        assertThat(info(petId).getName()).isEqualTo("小白");
        assertThat(player.wallet().balance(Wallet.GOLD)).isZero();
        assertThat(audit.currencies).extracting(RecordingAssetAudit.Currency::reason).containsExactly(Reason.PET_RENAME);
        assertThat(service.rename(player, petId, "小白")).isEqualTo(26015);
    }

    // ------------------------------------------------------------------ 主人等级 / 加载

    @Test
    void 主人升级宝宝跟着升_点数按等级换算_残血补上限增量_降级只夹() {
        long petId = grant(1);
        Pet pet = player.pets().find(petId);
        long max1 = info(petId).getDerived().getMaxHealth();
        pet.setHealth(max1 - 100);

        setLevel(30);

        PetInfo at30 = info(petId);
        assertThat(at30.getLevel()).isEqualTo(30);
        assertThat(at30.getTotalPoints()).isEqualTo(150);
        assertThat(at30.getDerived().getHealth()).as("补上限增量").isEqualTo(at30.getDerived().getMaxHealth() - 100);

        setLevel(1);
        assertThat(info(petId).getDerived().getHealth()).as("降级只夹").isEqualTo(info(petId).getDerived().getMaxHealth());
    }

    @Test
    void 降级后已分配超总量_整池清零返还() {
        long petId = grant(1);
        setLevel(30);
        assertThat(service.allocate(player, petId, Map.of(403, 150L))).isZero();
        setLevel(10);
        assertThat(info(petId).getRemainingPoints()).isEqualTo(50);
        assertThat(info(petId).getDimensionsList()).extracting(PetDimensionInfo::getAllocated).containsOnly(0);
    }

    @Test
    void 加载_纠正悬空的出战号_清掉不属于宝宝池的维度_按旧等级换算当前值() {
        PetState saved = PetState.newBuilder()
                .addPets(PetEntry.newBuilder().setPetId(5).setPetTableId(1).setLevel(1).setHealth(500).setMana(830)
                        .putAllocated(401, 2).putAllocated(103, 7)
                        .putAptitude(401, 8000).putAptitude(402, 10000).putAptitude(403, 7000).putAptitude(404, 11000))
                .setActivePetId(99)
                .build();
        player = WorldTestAccess.player(PLAYER, 30, PlayerState.newBuilder().setPets(saved).build());

        service.initializeOnLoad(player);

        PlayerPets pets = player.pets();
        assertThat(pets.activePetId()).isZero();
        Pet pet = pets.find(5);
        assertThat(pet.allocatedView()).containsExactly(Map.entry(401, 2L));
        assertThat(pet.level()).isEqualTo(30);
        PetInfo info = info(5);
        assertThat(info.getDerived().getHealth()).as("满血按比例换算到新上限仍是满").isEqualTo(info.getDerived().getMaxHealth());
    }

    @Test
    void 宝宝存档往返无损_没有宝宝时整段省略() {
        assertThat(player.pets().isPristine()).isTrue();
        long petId = grant(1);
        assertThat(service.summon(player, petId)).isZero();
        player.wallet().add(Wallet.GOLD, 200);
        assertThat(service.rename(player, petId, "小白")).isZero();
        assertThat(service.allocate(player, petId, Map.of(403, 3L))).isZero();
        PetState state = player.pets().toState();

        PlayerPets restored = PlayerPets.restore(state);

        assertThat(restored.toState()).isEqualTo(state);
        assertThat(restored.toState().toByteArray()).isEqualTo(state.toByteArray());
        assertThat(restored.activePetId()).isEqualTo(petId);
        assertThat(restored.find(petId).name()).isEqualTo("小白");
    }
}
