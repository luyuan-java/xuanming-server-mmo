package com.game.data.admin;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisKeys;
import com.game.discovery.drain.SceneEvacuationMarks;
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
import org.springframework.beans.BeansException;
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

/** 场景疏散管理面；共享 /admin/* 令牌、操作人与审计过滤器，Redis 懒加载，不影响审计服务启动。 */
@RestController
public class SceneEvacuationAdminController {
    public static final String PATH = "/admin/scenes";
    static final long DEFAULT_TTL_SECONDS = 3600;
    static final long MAX_TTL_SECONDS = 86_400;
    private static final Logger audit = LoggerFactory.getLogger(AdminAuthFilter.AUDIT_LOGGER);
    private static final Logger log = LoggerFactory.getLogger(SceneEvacuationAdminController.class);
    private final Supplier<NodeDirectory<SceneNodeInfo>> directory;
    private final Supplier<SceneEvacuationMarks> marks;
    private final long minTtlSeconds;

    @Autowired
    public SceneEvacuationAdminController(ObjectProvider<RedissonClient> redis, RedisKeys keys,
            @Value("${game.data.scene-evacuate.min-ttl:30m}") Duration minTtl) {
        this(() -> new NodeDirectory<>(redis.getObject(), keys, NodeTypes.SCENE, SceneNodeInfo.parser()),
                () -> new SceneEvacuationMarks(redis.getObject(), keys), minTtl);
    }

    SceneEvacuationAdminController(Supplier<NodeDirectory<SceneNodeInfo>> directory,
            Supplier<SceneEvacuationMarks> marks, Duration minTtl) {
        this.directory = memoize(directory);
        this.marks = memoize(marks);
        this.minTtlSeconds = minTtl.toSeconds();
        if (minTtlSeconds < 1 || minTtlSeconds > MAX_TTL_SECONDS) {
            throw new IllegalArgumentException("game.data.scene-evacuate.min-ttl 须在 1 s – 86400 s");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EvacuateBody(Long zoneId, Long nodeId, Long ttlSec, Boolean force) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ChannelView(String sceneId, long sceneConfigId, long playerCount, String kind, String state) {
        static ChannelView of(SceneEntry scene) {
            return new ChannelView(Long.toUnsignedString(scene.getSceneId()),
                    Integer.toUnsignedLong(scene.getSceneConfigId()), Integer.toUnsignedLong(scene.getPlayerCount()),
                    scene.getKind().name(), scene.getDraining() ? "DRAINING" : "ACTIVE");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SceneView(int nodeId, String instanceId, String leaseEpoch, boolean evacuating,
            Long evacuatingSince, boolean staleMark, long playerCount, List<ChannelView> channels, boolean evacuated) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EvacuateResult(int zoneId, int nodeId, String instanceId, boolean marked, Long evacuatingSince) {}

    @GetMapping(PATH + "/{zoneId}")
    public List<SceneView> list(@PathVariable("zoneId") int zoneId) {
        positive(zoneId, "zone_id");
        return redis(() -> {
            List<SceneNodeInfo> nodes = directory.get().list(zoneId);
            Map<Integer, SceneEvacuationMarks.Mark> found = marks.get().evacuating(zoneId,
                    nodes.stream().map(SceneNodeInfo::getNodeId).toList());
            return nodes.stream().sorted(Comparator.comparingInt(SceneNodeInfo::getNodeId)).map(node -> {
                SceneEvacuationMarks.Mark mark = found.get(node.getNodeId());
                boolean current = mark != null && mark.appliesTo(node.getInstanceId());
                long players = node.getScenesList().stream().mapToLong(s -> Integer.toUnsignedLong(s.getPlayerCount())).sum();
                return new SceneView(node.getNodeId(), node.getInstanceId(), Long.toUnsignedString(node.getLeaseEpoch()),
                        node.getEvacuating(), current ? mark.markedAtSec() : null, mark != null && !current,
                        players, node.getScenesList().stream().map(ChannelView::of).toList(),
                        current && node.getEvacuating() && players == 0);
            }).toList();
        });
    }

    @PostMapping(PATH + "/evacuate")
    public EvacuateResult evacuate(@RequestBody EvacuateBody body, HttpServletRequest request) {
        int zone = id(body.zoneId(), "zone_id");
        int nodeId = id(body.nodeId(), "node_id");
        long ttl = body.ttlSec() == null ? DEFAULT_TTL_SECONDS : body.ttlSec();
        if (ttl < minTtlSeconds || ttl > MAX_TTL_SECONDS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ttl_sec 须在 " + minTtlSeconds + "–" + MAX_TTL_SECONDS);
        }
        boolean force = Boolean.TRUE.equals(body.force());
        return redis(() -> {
            List<SceneNodeInfo> nodes = directory.get().list(zone);
            List<SceneNodeInfo> targets = nodes.stream().filter(n -> n.getZoneId() == zone && n.getNodeId() == nodeId).toList();
            if (targets.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目录里没有这台 scene");
            if (targets.size() != 1 || !validIdentity(targets.getFirst())) {
                throw new IllegalStateException("scene 目录身份不唯一或无效");
            }
            SceneNodeInfo target = targets.getFirst();
            Map<Integer, String> others = new HashMap<>();
            for (SceneNodeInfo node : nodes) {
                // 已自报疏散或地址无效的节点不能充当最后一台的后备容量。
                if (node.getNodeId() != nodeId && node.getZoneId() == zone && !node.getEvacuating()
                        && validIdentity(node) && !node.getLinkHost().isBlank()
                        && node.getLinkPort() > 0 && node.getLinkPort() <= 65535) {
                    if (others.putIfAbsent(node.getNodeId(), node.getInstanceId()) != null) {
                        throw new IllegalStateException("scene 目录节点身份重复");
                    }
                }
            }
            SceneEvacuationMarks.MarkResult result = marks.get().mark(zone, nodeId, target.getInstanceId(),
                    Duration.ofSeconds(ttl), force ? null : others);
            audit.warn("scene evacuate zone_id={} node_id={} instance={} result={} ttl_sec={} force={} operator={}",
                    zone, nodeId, target.getInstanceId(), result, ttl, force, ZoneAdminController.operator(request));
            if (result == SceneEvacuationMarks.MarkResult.LAST_NODE) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "last_node：本区没有其他可接客 scene，确认要打带 force=true");
            }
            if (result == SceneEvacuationMarks.MarkResult.EXISTING_INVALID) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "existing_mark_invalid：同实例标记损坏或无 TTL，先 DELETE 再打");
            }
            if (result == SceneEvacuationMarks.MarkResult.TARGET_CHANGED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "target_changed：目标实例租约已经失效或换代，请刷新目录");
            }
            if (result == SceneEvacuationMarks.MarkResult.ZONE_RETIRED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "zone_retired：区服已永久退役");
            }
            SceneEvacuationMarks.Mark mark = marks.get().read(zone, nodeId);
            return new EvacuateResult(zone, nodeId, target.getInstanceId(), result == SceneEvacuationMarks.MarkResult.MARKED,
                    mark != null && mark.appliesTo(target.getInstanceId()) ? mark.markedAtSec() : null);
        });
    }

    @DeleteMapping(PATH + "/evacuate/{zoneId}/{nodeId}")
    public ResponseEntity<Void> cancel(@PathVariable("zoneId") int zoneId, @PathVariable("nodeId") int nodeId,
            HttpServletRequest request) {
        positive(zoneId, "zone_id");
        positive(nodeId, "node_id");
        redis(() -> { marks.get().clear(zoneId, nodeId); return null; });
        audit.warn("scene evacuation cancel zone_id={} node_id={} operator={}", zoneId, nodeId,
                ZoneAdminController.operator(request));
        return ResponseEntity.noContent().build();
    }

    private static boolean validIdentity(SceneNodeInfo node) {
        return node.getNodeId() > 0 && !node.getInstanceId().isBlank() && node.getInstanceId().indexOf(':') < 0;
    }

    private static int id(Long value, String name) {
        if (value == null || value <= 0 || value > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " 须在 1–2147483647");
        }
        return value.intValue();
    }

    private static void positive(int value, String name) {
        id((long) value, name);
    }

    private static <T> T redis(Supplier<T> call) {
        try {
            return call.get();
        } catch (RedisException | BeansException | IllegalStateException e) {
            log.warn("场景疏散运维接口：存储或目录不可用", e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Redis 或 scene 目录不可用", e);
        }
    }

    private static <T> Supplier<T> memoize(Supplier<T> factory) {
        return new Supplier<>() {
            private volatile T value;
            @Override public T get() {
                T current = value;
                if (current == null) synchronized (this) {
                    current = value;
                    if (current == null) value = current = factory.get();
                }
                return current;
            }
        };
    }
}
