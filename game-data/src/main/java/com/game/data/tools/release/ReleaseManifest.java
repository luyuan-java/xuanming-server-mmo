package com.game.data.tools.release;

import java.util.List;
import java.util.Map;

/** 离线原件，不存在用户可填的 healthy/applied 标志。源码与 jar 的来源声明不是可重复构建证明。 */
public record ReleaseManifest(int format, String kind, String createdAt, ReleaseSpec plan,
                              String mavenVersion, String javaVersion, String springBootVersion,
                              String migrationDocumentHead, Map<String, FileEntry> files,
                              Map<String, Map<String, String>> jarBuildInfo,
                              List<String> notCovered) {
    public ReleaseManifest {
        files = Map.copyOf(files);
        var copied = new java.util.TreeMap<String, Map<String, String>>();
        jarBuildInfo.forEach((module, values) -> copied.put(module, Map.copyOf(values)));
        jarBuildInfo = Map.copyOf(copied);
        notCovered = List.copyOf(notCovered);
    }
    public record FileEntry(long bytes, String sha256, String gitBlob) { }
    static final String KIND = "java-offline-jar-release-v1";
    static final List<String> NOT_COVERED = List.of(
            "reproducible_build_or_signed_provenance", "java_tests_and_client_smoke",
            "oci_archive_layers_build_info_and_jar_binding", "registry_digest_and_tag_immutability",
            "build_release_stamp_when_absent", "contract_semantic_compatibility_and_persistent_schema_ledger",
            "live_sql_schema_and_route_generation", "live_kafka_generation_partitions_replication_isr",
            "complete_runtime_config_resource_plan_and_secret_reference_matrix",
            "runtime_effective_config_and_secret_injection", "kubernetes_workload_identity_and_old_writers_stopped",
            "deployment_drain_rollout_rollback", "capacity_horizontal_scaling_and_ha_rpo_rto");
}
