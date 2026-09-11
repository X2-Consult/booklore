package org.booklore.service.metadata.parser;

import org.booklore.model.enums.MetadataProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataProviderGuardTest {

    private long now;
    private final List<Long> sleeps = new ArrayList<>();
    private MetadataProviderGuard guard;

    @BeforeEach
    void setUp() {
        now = 1_000_000_000L;
        sleeps.clear();
        // The fake sleeper advances the fake clock, like a real sleep would.
        guard = new MetadataProviderGuard(() -> now, nanos -> {
            sleeps.add(nanos);
            now += nanos;
        });
    }

    private void advance(Duration duration) {
        now += duration.toNanos();
    }

    @Test
    void firstRequestGoesImmediately_laterOnesAreSpacedByTheGapPlusJitter() {
        MetadataProviderGuard.Pacing pacing = MetadataProviderGuard.PACING.get(MetadataProvider.Amazon);

        guard.awaitTurn(MetadataProvider.Amazon);
        guard.awaitTurn(MetadataProvider.Amazon);
        guard.awaitTurn(MetadataProvider.Amazon);

        assertThat(sleeps).hasSize(2);
        assertThat(sleeps).allSatisfy(nanos -> assertThat(nanos)
                .isBetween(pacing.minGap().toNanos(), pacing.minGap().plus(pacing.jitter()).toNanos()));
    }

    @Test
    void noWaitOnceTheGapHasAlreadyPassed() {
        guard.awaitTurn(MetadataProvider.GoodReads);
        advance(Duration.ofSeconds(5));

        guard.awaitTurn(MetadataProvider.GoodReads);

        assertThat(sleeps).isEmpty();
    }

    @Test
    void providersArePacedIndependently_andUnpacedProvidersNeverWait() {
        guard.awaitTurn(MetadataProvider.Amazon);
        guard.awaitTurn(MetadataProvider.GoodReads);
        guard.awaitTurn(MetadataProvider.Google);
        guard.awaitTurn(MetadataProvider.Google);

        assertThat(sleeps).isEmpty();
    }

    @Test
    void blockLastsForTheCooldownThenExpires() {
        Duration cooldown = MetadataProviderGuard.BLOCK_COOLDOWN.get(MetadataProvider.GoodReads);

        guard.markBlocked(MetadataProvider.GoodReads);
        assertThat(guard.isBlocked(MetadataProvider.GoodReads)).isTrue();
        assertThat(guard.isBlocked(MetadataProvider.Amazon)).isFalse();

        advance(cooldown.minusSeconds(1));
        assertThat(guard.isBlocked(MetadataProvider.GoodReads)).isTrue();

        advance(Duration.ofSeconds(2));
        assertThat(guard.isBlocked(MetadataProvider.GoodReads)).isFalse();
    }

    @Test
    void blockTiedToACookieDoesNotApplyToANewCookie() {
        guard.markBlocked(MetadataProvider.Amazon, "session-id=old");

        assertThat(guard.isBlocked(MetadataProvider.Amazon, "session-id=old")).isTrue();
        assertThat(guard.isBlocked(MetadataProvider.Amazon, "  session-id=old  ")).isTrue();
        assertThat(guard.isBlocked(MetadataProvider.Amazon, "session-id=new")).isFalse();
        assertThat(guard.isBlocked(MetadataProvider.Amazon, null)).isFalse();
        assertThat(guard.isBlocked(MetadataProvider.Amazon)).isTrue();
    }

    @Test
    void blankCookieCountsAsNoCookie() {
        guard.markBlocked(MetadataProvider.Amazon, null);

        assertThat(guard.isBlocked(MetadataProvider.Amazon, "")).isTrue();
        assertThat(guard.isBlocked(MetadataProvider.Amazon, "   ")).isTrue();
    }

    @Test
    void clearBlockEndsTheCooldownEarly() {
        guard.markBlocked(MetadataProvider.Amazon, null);

        guard.clearBlock(MetadataProvider.Amazon);

        assertThat(guard.isBlocked(MetadataProvider.Amazon)).isFalse();
    }
}
