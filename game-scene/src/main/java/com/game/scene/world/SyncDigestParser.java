package com.game.scene.world;

import com.game.proto.SyncDigestEntry;
import com.game.proto.SyncDigestS2C;
import com.game.proto.SyncDigestStatus;
import com.game.proto.SyncDomain;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 落地同步版本摘要 Hash 解析器（纯函数，逐条对齐 C++ {@code PlayerLandingSystem::ParseSyncDigestFields}）。
 */
public final class SyncDigestParser {

    public static final String GENERATION_FIELD = "g";

    private SyncDigestParser() {
    }

    /**
     * 解析 Redis {@code HGETALL <ns>:player:{<player_id>}:sync_digest} 结果。
     *
     * @return 空表示应答畸形（调用方须推 {@code SYNC_DIGEST_STATUS_UNAVAILABLE} 令客户端全量拉取）。
     */
    public static Optional<SyncDigestS2C> parse(Map<String, String> fields) {
        if (fields == null) {
            return Optional.empty();
        }
        if (fields.isEmpty()) {
            return Optional.of(SyncDigestS2C.newBuilder()
                    .setStatus(SyncDigestStatus.SYNC_DIGEST_STATUS_OK)
                    .setGeneration(0)
                    .build());
        }
        boolean hasGeneration = false;
        long generation = 0;
        Set<SyncDomain> seen = new HashSet<>();
        SyncDigestS2C.Builder out = SyncDigestS2C.newBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || value == null) {
                return Optional.empty();
            }
            if (GENERATION_FIELD.equals(name)) {
                if (hasGeneration) {
                    return Optional.empty();
                }
                Long parsed = parseDecimalUint64(value);
                if (parsed == null) {
                    return Optional.empty();
                }
                generation = parsed;
                hasGeneration = true;
                continue;
            }
            SyncDomain domain = knownDomainOf(name);
            if (domain == null) {
                // 新版本服务端可能写入未知域号：向前兼容直接跳过
                continue;
            }
            Long version = parseDecimalUint64(value);
            if (version == null || !seen.add(domain)) {
                return Optional.empty();
            }
            out.addEntries(SyncDigestEntry.newBuilder().setDomain(domain).setVersion(version));
        }
        if (!hasGeneration || generation == 0) {
            return Optional.empty();
        }
        return Optional.of(out.setStatus(SyncDigestStatus.SYNC_DIGEST_STATUS_OK)
                .setGeneration(generation)
                .build());
    }

    public static SyncDigestS2C unavailable() {
        return SyncDigestS2C.newBuilder()
                .setStatus(SyncDigestStatus.SYNC_DIGEST_STATUS_UNAVAILABLE)
                .build();
    }

    private static SyncDomain knownDomainOf(String name) {
        if (name.isEmpty() || (name.length() > 1 && name.charAt(0) == '0')) {
            return null;
        }
        Long number = parseDecimalUint64(name);
        if (number == null || Long.compareUnsigned(number, Integer.MAX_VALUE) > 0) {
            return null;
        }
        int code = number.intValue();
        if (code == SyncDomain.SYNC_DOMAIN_UNSPECIFIED_VALUE) {
            return null;
        }
        return SyncDomain.forNumber(code);
    }

    private static Long parseDecimalUint64(String text) {
        if (text.isEmpty()) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        try {
            return Long.parseUnsignedLong(text, 10);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
