package com.game.gateway.hotfix;

import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 无 I/O 的发布线查询；实例和配置均不可变，可由 Servlet 线程并发调用。 */
public final class HotfixCheckService {
    private final Map<String, HotfixProperties.Release> releases;

    public HotfixCheckService(HotfixProperties properties) {
        releases = Objects.requireNonNull(properties).platforms();
    }

    public HotfixCheckResponse check(HotfixCheckRequest request) {
        if (request == null || request.platform() == null || request.platform().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "platform is required");
        }
        NumericVersion client;
        try {
            client = NumericVersion.parse(request.clientVersion());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "numeric client_version is required", invalid);
        }
        HotfixProperties.Release release = releases.get(request.platform());
        if (release == null) {
            return new HotfixCheckResponse(false, false, null, null, null);
        }
        if (client.compareTo(NumericVersion.parse(release.latestVersion())) >= 0) {
            return new HotfixCheckResponse(false, false, null, release.latestVersion(), null);
        }
        boolean forced = release.minVersion() != null
                && client.compareTo(NumericVersion.parse(release.minVersion())) < 0;
        return new HotfixCheckResponse(true, forced, release.patchUrl(), release.latestVersion(), release.changelog());
    }
}
