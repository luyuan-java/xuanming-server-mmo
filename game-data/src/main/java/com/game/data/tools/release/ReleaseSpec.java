package com.game.data.tools.release;

import java.util.Map;

/** 输入只描述离线计划；没有 applied/healthy 等可伪造的在线结论字段。秘密只传环境变量的引用版本。 */
public record ReleaseSpec(int format, String version, String javaCommit, String contractCommit, String mapsCommit,
                          String namespace, String routeGeneration, String directoryProtocol, String rolloutStrategy,
                          Map<String, String> jars, Map<String, Map<String, String>> settings,
                          Map<String, String> secretVersions, Map<String, String> migrationPlans) {
    public ReleaseSpec {
        jars = Map.copyOf(jars);
        var copied = new java.util.TreeMap<String, Map<String, String>>();
        settings.forEach((module, values) -> copied.put(module, Map.copyOf(values)));
        settings = Map.copyOf(copied);
        secretVersions = Map.copyOf(secretVersions);
        migrationPlans = Map.copyOf(migrationPlans);
    }
}
