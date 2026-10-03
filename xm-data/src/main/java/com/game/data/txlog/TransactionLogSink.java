package com.game.data.txlog;

import com.game.data.consume.ConsumerLoop.Sink;
import com.game.data.store.TransactionLogMapper;
import java.time.Clock;
import java.util.List;
import org.springframework.transaction.support.TransactionTemplate;

/** 资产流水落库：一批行在一个事务里分块多行插入（主键幂等）。 */
public final class TransactionLogSink implements Sink<TransactionLogRow> {

    private final TransactionLogMapper mapper;
    private final TransactionTemplate tx;
    private final int chunk;
    private final Clock clock;

    public TransactionLogSink(TransactionLogMapper mapper, TransactionTemplate tx, int chunk, Clock clock) {
        this.mapper = mapper;
        this.tx = tx;
        this.chunk = chunk;
        this.clock = clock;
    }

    @Override
    public int insert(List<TransactionLogRow> rows) {
        long ingestedAt = clock.millis();
        Integer inserted = tx.execute(status -> {
            int total = 0;
            for (int from = 0; from < rows.size(); from += chunk) {
                total += mapper.insertAll(rows.subList(from, Math.min(rows.size(), from + chunk)), ingestedAt);
            }
            return total;
        });
        // 受影响行数只在 useAffectedRows=true 时等于新插入数；兜底夹到 [0, rows]
        return Math.max(0, Math.min(rows.size(), inserted == null ? 0 : inserted));
    }
}
