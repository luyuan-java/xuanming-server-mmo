package com.game.data.admin;

import com.game.data.consume.AuditGroupProbe;
import com.game.data.consume.AuditOffsetSnapshot;
import com.game.data.consume.AuditProbeState;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 有鉴权的内存原件查询，不触发 broker I/O；HTTP 200 只表示查询成功，业务状态由 status 明示。 */
@RestController
public final class AuditProbeAdminController {
    public static final String PATH = "/admin/audit/consumer-probes";
    private final AuditGroupProbe probe;

    public AuditProbeAdminController(AuditGroupProbe probe) { this.probe = probe; }

    public record PartitionView(int partition, String beginning, String committed, String end,
                                String lag, String retentionGap) {}
    public record Evidence(String topicId, String lag, String maxPartitionLag, String confirmedGapLowerBound,
                           int uncommittedPartitions, List<PartitionView> partitions) {}
    public record ProbeView(String consumer, String topic, int generation, String group, int expectedPartitions,
                            String status, String reason, Double ageSeconds, long lastAttemptMillis,
                            long lastSuccessMillis, Evidence lastSample, Evidence lastGapEvidence,
                            String gapHighWater) {}

    @GetMapping(PATH)
    public List<ProbeView> view(HttpServletRequest request) {
        // 即使漏装 Servlet Filter 或有人直接调用 handler，也不能只凭请求头绕过鉴权标记。
        AdminAuthFilter.authenticatedOperator(request);
        return probe.views().stream().map(AuditProbeAdminController::render).toList();
    }

    private static ProbeView render(AuditProbeState.View view) {
        var target = view.target();
        return new ProbeView(target.consumer().label(), target.topic(), target.generation(), target.group(),
                target.partitionCount(), view.status().name(), view.reason(),
                Double.isFinite(view.ageSeconds()) ? view.ageSeconds() : null,
                view.lastAttemptMillis(), view.lastSuccessMillis(), render(view.lastSample()),
                render(view.lastGapEvidence()), Long.toString(view.gapHighWater()));
    }

    private static Evidence render(AuditOffsetSnapshot sample) {
        if (sample == null) return null;
        return new Evidence(sample.topicId(), Long.toString(sample.lag()), Long.toString(sample.maxPartitionLag()),
                Long.toString(sample.retentionGap()), sample.uncommittedPartitions(), sample.partitions().stream()
                .map(row -> new PartitionView(row.partition(), Long.toString(row.beginning()), text(row.committed()),
                        Long.toString(row.end()), Long.toString(row.lag()), text(row.retentionGap()))).toList());
    }

    private static String text(Long value) { return value == null ? null : Long.toString(value); }
}
