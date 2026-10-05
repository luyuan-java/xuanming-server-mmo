package com.game.scene.testing;

import com.game.scene.world.PlayerData;
import com.game.scene.world.PlayerRepository;
import com.game.scene.world.PlayerSave;
import com.game.scene.world.PlayerRepository.HandOffOutcome;
import com.game.scene.world.PlayerRepository.ProbeOutcome;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.scene.world.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 内存版玩家存储。加载不立即回调（与真实实现一样异步）：测试显式调用 {@link #completeAll()} 或逐个完成，
 * 以便构造「加载中离开」「乱序返回」等时序。
 */
public final class FakePlayerRepository implements PlayerRepository {

    /** 一次挂起的加载。 */
    public record PendingLoad(long playerId, Consumer<LoadResult> callback) {

        public void complete(LoadResult result) {
            callback.accept(result);
        }
    }

    /** 一次只释放归属（不写状态）。 */
    public record Release(long playerId, long ownerEpoch) {
    }

    private final Deque<PendingLoad> loads = new ArrayDeque<>();
    private final Map<Long, PlayerData> rows = new HashMap<>();
    private final List<PlayerSave> saves = new ArrayList<>();
    private final List<Release> releases = new ArrayList<>();

    @Override
    public void load(long playerId, Consumer<LoadResult> onLoaded) {
        loads.add(new PendingLoad(playerId, onLoaded));
    }

    @Override
    public void save(PlayerSave save) {
        saves.add(save);
    }

    @Override
    public void release(long playerId, long ownerEpoch) {
        releases.add(new Release(playerId, ownerEpoch));
    }

    /** 一次挂起的在线存盘：测试决定何时、以什么结局完成（模拟结果投递回逻辑线程）。 */
    public record PendingProgress(PlayerSave save, Consumer<ProgressResult> callback) {

        public void complete(ProgressResult result) {
            callback.accept(result);
        }
    }

    private final Deque<PendingProgress> progress = new ArrayDeque<>();
    private boolean acceptsProgress = true;

    @Override
    public boolean acceptsProgress() {
        return acceptsProgress;
    }

    /** 模拟存储积压（false = 不接在线存盘）。 */
    public void setAcceptsProgress(boolean accepts) {
        this.acceptsProgress = accepts;
    }

    @Override
    public void saveProgress(PlayerSave save, Consumer<ProgressResult> onDone) {
        progress.add(new PendingProgress(save, onDone));
    }

    /** 取出最早一个挂起的在线存盘。 */
    public PendingProgress takeProgress() {
        PendingProgress p = progress.poll();
        if (p == null) {
            throw new IllegalStateException("没有挂起的在线存盘");
        }
        return p;
    }

    public int pendingProgress() {
        return progress.size();
    }

    /** 一次挂起的交出：测试决定何时、以什么结局完成（模拟结局投递回逻辑线程）。 */
    public record PendingHandOff(PlayerSave frozen, Consumer<HandOffOutcome> callback) {

        public void complete(HandOffOutcome outcome) {
            callback.accept(outcome);
        }
    }

    /** 一次挂起的探测：测试决定何时、以什么结局完成。 */
    public record PendingProbe(HandOffOutcome.Failed failed, Consumer<ProbeOutcome> callback) {

        public void complete(ProbeOutcome outcome) {
            callback.accept(outcome);
        }
    }

    private final Deque<PendingHandOff> handOffs = new ArrayDeque<>();
    private final Deque<PendingProbe> probes = new ArrayDeque<>();

    @Override
    public void handOff(PlayerSave frozen, Consumer<HandOffOutcome> onDone) {
        handOffs.add(new PendingHandOff(frozen, onDone));
    }

    @Override
    public void probe(HandOffOutcome.Failed failed, Consumer<ProbeOutcome> onDone) {
        probes.add(new PendingProbe(failed, onDone));
    }

    /** 取出最早一个挂起的交出。 */
    public PendingHandOff takeHandOff() {
        PendingHandOff h = handOffs.poll();
        if (h == null) {
            throw new IllegalStateException("没有挂起的交出");
        }
        return h;
    }

    public int pendingHandOffs() {
        return handOffs.size();
    }

    /** 取出最早一个挂起的探测。 */
    public PendingProbe takeProbe() {
        PendingProbe p = probes.poll();
        if (p == null) {
            throw new IllegalStateException("没有挂起的探测");
        }
        return p;
    }

    public int pendingProbes() {
        return probes.size();
    }

    public List<Release> releases() {
        return releases;
    }

    /** 放一行存档；覆盖同 id 的旧行。 */
    public void put(PlayerData data) {
        rows.put(data.playerId(), data);
    }

    /** 新号：从未进过场景、坐标 (0,0,0)、等级 1，职业 3 / 性别 1 / 外观 look-{id}。 */
    public PlayerData putNewPlayer(long playerId, long ownerEpoch) {
        PlayerData data = new PlayerData(playerId, ownerEpoch, 3, 1, "look-" + playerId, 1, 0, Vec3.ORIGIN);
        put(data);
        return data;
    }

    /** 老号：存档在 {@code sceneConfigId} 的 {@code position}。 */
    public PlayerData putSavedPlayer(long playerId, long ownerEpoch, int sceneConfigId, Vec3 position) {
        PlayerData data = new PlayerData(playerId, ownerEpoch, 2, 0, "look-" + playerId, 5, sceneConfigId, position);
        put(data);
        return data;
    }

    /** 按当前存档完成全部挂起的加载（模拟存储线程返回后投递回逻辑线程）。 */
    public void completeAll() {
        while (!loads.isEmpty()) {
            PendingLoad load = loads.poll();
            PlayerData data = rows.get(load.playerId());
            load.complete(data == null ? new LoadResult.NotFound() : new LoadResult.Found(data));
        }
    }

    /** 取出最早一个挂起的加载，由测试决定何时、以什么结果完成。 */
    public PendingLoad takeLoad() {
        PendingLoad load = loads.poll();
        if (load == null) {
            throw new IllegalStateException("没有挂起的加载");
        }
        return load;
    }

    public int pendingLoads() {
        return loads.size();
    }

    public List<PlayerSave> saves() {
        return saves;
    }
}
