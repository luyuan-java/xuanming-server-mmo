package com.game.data.admin;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.game.api.proto.GateNodeInfo;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.discovery.drain.GateDrainMarks;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 运维：gate 排空（同 mmorpg gatedrain + k8s_gate_drain.ps1 的打标记 / 查状态 / 撤销三步）。
 * <ul>
 *   <li>{@code GET /admin/gates/{zoneId}}：本区 gate 目录（节点号、实例、地址、在线人数）叠加排空起点、drained 理由；
 *       标记是旧实例留下的（节点号已被新实例复用）显示 {@code stale_mark=true}，判定循环会清掉它；</li>
 *   <li>{@code POST /admin/gates/drain {zone_id, node_id, ttl_sec?=3600, force?}}：给目录里这台 gate 的<b>当前实例</b>打排空标记
 *       （Redis 服务器时间；同实例已在排空时起点不变、回 {@code marked=false}）。目录里没有 → 404；TTL 须在
 *       [{@code xm.data.gate-drain.min-ttl}（缺省 30 min，须长于 gateway 的排空 deadline，否则标记先过期、永远判不到 drained），
 *       86400 s]（同基线脚本上限）→ 否则 400；打上之后本区没有接客的 gate → 409 {@code last_gate}（检查与写在同一段 Lua 里，
 *       两个并发请求不会都通过、把全区标满；同基线脚本），{@code force=true} 才打；</li>
 *   <li>{@code DELETE /admin/gates/drain/{zoneId}/{nodeId}}：撤销（两个标记一起删），恒 204。</li>
 * </ul>
 * 判定「可下线」（drained）由 xm-gateway 的判定循环写；这里不踢人、不删实例。写操作进审计日志。
 */
@RestController
public class GateDrainAdminController {

    public static final String PATH = "/admin/gates";
    static final long DEFAULT_TTL_SECONDS = 3600;
    static final long MAX_TTL_SECONDS = 86_400;

    private static final Logger audit = LoggerFactory.getLogger(AdminAuthFilter.AUDIT_LOGGER);
    private static final Logger log = LoggerFactory.getLogger(GateDrainAdminController.class);

    /** Redis 客户端是懒加载的（xm-data 的主业务是审计落库，Redis 不可达不能挡启动），第一次调用这些接口时才建。 */
    private final Supplier<NodeDirectory<GateNodeInfo>> gateDirectory;
    private final Supplier<GateDrainMarks> drainMarks;
    private final long minTtlSeconds;

    @Autowired
    public GateDrainAdminController(ObjectProvider<RedissonClient> redis,
                                    @Value("${xm.data.gate-drain.min-ttl:30m}") Duration minTtl) {
        this(() -> new NodeDirectory<>(redis.getObject(), NodeTypes.GATE, GateNodeInfo.parser()),
                () -> new GateDrainMarks(redis.getObject()), minTtl);
    }

    GateDrainAdminController(Supplier<NodeDirectory<GateNodeInfo>> gates, Supplier<GateDrainMarks> marks, Duration minTtl) {
        this.gateDirectory = memoize(gates);
        this.drainMarks = memoize(marks);
        this.minTtlSeconds = minTtl.toSeconds();
        if (minTtlSeconds < 1 || minTtlSeconds > MAX_TTL_SECONDS) {
            throw new IllegalArgumentException("xm.data.gate-drain.min-ttl 须在 1 s – 86400 s");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DrainBody(Long zoneId, Long nodeId, Long ttlSec, Boolean force) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GateView(int nodeId, String instanceId, String clientHost, int clientPort, long playerCount,
                           Long drainingSince, String drained, boolean staleMark) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DrainResult(int zoneId, int nodeId, String instanceId, boolean marked, Long drainingSince) {
    }

    @GetMapping(PATH + "/{zoneId}")
    public List<GateView> list(@PathVariable("zoneId") int zoneId) {
        return redis(() -> {
            List<GateNodeInfo> list = gateDirectory.get().list(zoneId);
            List<Integer> nodes = list.stream().map(GateNodeInfo::getNodeId).toList();
            Map<Integer, GateDrainMarks.Mark> draining = drainMarks.get().draining(zoneId, nodes);
            Map<Integer, String> drained = drainMarks.get().drained(zoneId, nodes);
            return list.stream()
                    .sorted(Comparator.comparingInt(GateNodeInfo::getNodeId))
                    .map(g -> {
                        GateDrainMarks.Mark mark = draining.get(g.getNodeId());
                        boolean current = mark != null && mark.appliesTo(g.getInstanceId());
                        return new GateView(g.getNodeId(), g.getInstanceId(), g.getClientHost(), g.getClientPort(),
                                Integer.toUnsignedLong(g.getPlayerCount()), current ? mark.markedAtSec() : null,
                                current ? drained.get(g.getNodeId()) : null, mark != null && !current);
                    })
                    .toList();
        });
    }

    @PostMapping(PATH + "/drain")
    public DrainResult drain(@RequestBody DrainBody body, HttpServletRequest request) {
        if (body.zoneId() == null || body.nodeId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "zone_id 与 node_id 必填");
        }
        if (body.zoneId() <= 0 || body.zoneId() > Integer.MAX_VALUE
                || body.nodeId() <= 0 || body.nodeId() > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "zone_id / node_id 须在 1–2147483647");
        }
        long ttl = body.ttlSec() == null ? DEFAULT_TTL_SECONDS : body.ttlSec();
        if (ttl < minTtlSeconds || ttl > MAX_TTL_SECONDS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ttl_sec 须在 " + minTtlSeconds + "–" + MAX_TTL_SECONDS
                    + "（须长于 gateway 的排空 deadline）");
        }
        int zoneId = body.zoneId().intValue();
        int nodeId = body.nodeId().intValue();
        boolean force = Boolean.TRUE.equals(body.force());
        return redis(() -> {
            List<GateNodeInfo> list = gateDirectory.get().list(zoneId);
            GateNodeInfo target = list.stream().filter(g -> g.getNodeId() == nodeId).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "目录里没有这台 gate"));
            Map<Integer, String> others = new HashMap<>();
            for (GateNodeInfo g : list) {
                if (g.getNodeId() != nodeId) {
                    others.put(g.getNodeId(), g.getInstanceId());
                }
            }
            GateDrainMarks.MarkResult result = drainMarks.get().mark(zoneId, nodeId, target.getInstanceId(),
                    Duration.ofSeconds(ttl), force ? null : others);
            audit.warn("gate drain zone_id={} node_id={} instance={} result={} ttl_sec={} force={} operator={}", zoneId,
                    nodeId, target.getInstanceId(), result, ttl, force, ZoneAdminController.operator(request));
            if (result == GateDrainMarks.MarkResult.LAST_GATE) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "last_gate：打上之后本区没有接客的 gate 了"
                        + "（gateway 会忽略全部标记照常分配），确认要打带 force=true");
            }
            if (result == GateDrainMarks.MarkResult.EXISTING_INVALID) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "existing_mark_invalid：已有的排空标记没有 TTL"
                        + "（不会过期），先 DELETE 再打");
            }
            GateDrainMarks.Mark mark = drainMarks.get().draining(zoneId, List.of(nodeId)).get(nodeId);
            return new DrainResult(zoneId, nodeId, target.getInstanceId(), result == GateDrainMarks.MarkResult.MARKED,
                    mark == null ? null : mark.markedAtSec());
        });
    }

    @DeleteMapping(PATH + "/drain/{zoneId}/{nodeId}")
    public ResponseEntity<Void> undrain(@PathVariable("zoneId") int zoneId, @PathVariable("nodeId") int nodeId,
                                        HttpServletRequest request) {
        redis(() -> {
            drainMarks.get().clear(zoneId, nodeId);
            return null;
        });
        audit.warn("gate undrain zone_id={} node_id={} operator={}", zoneId, nodeId, ZoneAdminController.operator(request));
        return ResponseEntity.noContent().build();
    }

    /** Redis 不可达（含懒加载的客户端建不起来）→ 503。 */
    private static <T> T redis(Supplier<T> call) {
        try {
            return call.get();
        } catch (RedisException | BeanCreationException e) {
            log.warn("gate 排空运维接口：Redis 调用失败", e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Redis 不可用", e);
        }
    }

    private static <T> Supplier<T> memoize(Supplier<T> factory) {
        return new Supplier<>() {
            private volatile T value;

            @Override
            public T get() {
                T v = value;
                if (v == null) {
                    synchronized (this) {
                        v = value;
                        if (v == null) {
                            v = factory.get();
                            value = v;
                        }
                    }
                }
                return v;
            }
        };
    }
}
