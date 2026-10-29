package com.game.data.consistency;

import java.time.Instant;
import java.util.List;

/** 一次有界观察；发现与未知覆盖独立保存，不把部分扫描当全库快照。 */
public record ConsistencyReport(String runId, String namespace, Instant startedAt, Instant finishedAt,
        boolean finalized, String observation, List<String> requested, List<String> executed,
        List<String> omitted, List<Result> results, int exitCode) {
    public enum Status { PASS, WARN, BLOCK, UNKNOWN }
    public enum Coverage { COMPLETE, PARTIAL, NONE }
    public record Sample(String identityHash, String reason) {}
    public record Result(String check, Status status, Coverage coverage, boolean countExact,
            long scannedCount, long confirmedBad, long transientCount, List<String> reasons,
            List<Sample> samples, String cursorHash, String upperHash, Long firstSqlMillis, Long lastSqlMillis) {
        public Result { reasons = List.copyOf(reasons); samples = List.copyOf(samples); }
        public static Result unknown(String id, String reason) {
            return new Result(id, Status.UNKNOWN, Coverage.NONE, false, 0, 0, 0,
                    List.of(reason), List.of(), null, null, null, null);
        }
    }
    public ConsistencyReport {
        requested = List.copyOf(requested); executed = List.copyOf(executed);
        omitted = List.copyOf(omitted); results = List.copyOf(results);
    }
    public static int exitCode(List<Result> results, boolean failOnWarn) {
        if (results.stream().anyMatch(r -> r.status == Status.BLOCK || (failOnWarn && r.status == Status.WARN))) return 1;
        return results.stream().anyMatch(r -> r.coverage != Coverage.COMPLETE || r.status == Status.UNKNOWN) ? 3 : 0;
    }
}
