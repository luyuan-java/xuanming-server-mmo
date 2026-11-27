package com.game.player.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.player.store.SaveRowGuard.SaveRejectionKind;
import com.game.player.store.SaveRowGuard.UnknownSaveRowKindException;
import java.sql.SQLException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SaveRowGuardTest {

    @Test
    void unknownRowKindIsRetryableVersionTooNewAndClassifiesDeterministicRefusals() {
        SaveRowGuard.requireKnownRowKind(1, Set.of(1, 2, 3));
        assertThatThrownBy(() -> SaveRowGuard.requireKnownRowKind(99, Set.of(1, 2, 3)))
                .isInstanceOf(UnknownSaveRowKindException.class)
                .satisfies(e -> {
                    UnknownSaveRowKindException ex = (UnknownSaveRowKindException) e;
                    assertThat(ex.rowKind()).isEqualTo(99);
                    assertThat(ex.retryable()).isTrue();
                });

        assertThat(SaveRowGuard.classifyRejection(new SQLException("Entry too large", "HY000", 8025)))
                .isEqualTo(SaveRejectionKind.TOO_LARGE);
        assertThat(SaveRowGuard.classifyRejection(new SQLException("Got a packet bigger than max_allowed_packet", "08S01", 1153)))
                .isEqualTo(SaveRejectionKind.TOO_LARGE);
        assertThat(SaveRowGuard.classifyRejection(new SQLException("Data too long", "22001", 1406)))
                .isEqualTo(SaveRejectionKind.INVALID);
        assertThat(SaveRowGuard.classifyRejection(new RuntimeException("unexpected constraint")))
                .isEqualTo(SaveRejectionKind.OTHER);
    }
}
