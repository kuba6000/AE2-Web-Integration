package pl.kuba6000.ae2webintegration.core.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import pl.kuba6000.ae2webintegration.core.api.IConfigValue;
import pl.kuba6000.ae2webintegration.core.config.ConfigBootstrap;

class DiscordManagerTest {

    private IConfigValue<Integer> previousMinimumDuration;
    private IConfigValue<Integer> previousMinimumAmount;

    @BeforeEach
    void resetConfig() {
        previousMinimumDuration = ConfigBootstrap.discordMinimumCraftingDurationSecondsValue;
        previousMinimumAmount = ConfigBootstrap.discordMinimumCraftingAmountValue;
        ConfigBootstrap.discordMinimumCraftingDurationSecondsValue = () -> 0;
        ConfigBootstrap.discordMinimumCraftingAmountValue = () -> 0;
    }

    @AfterEach
    void restoreConfig() {
        ConfigBootstrap.discordMinimumCraftingDurationSecondsValue = previousMinimumDuration;
        ConfigBootstrap.discordMinimumCraftingAmountValue = previousMinimumAmount;
    }

    @ParameterizedTest
    @CsvSource({ "250, 0.25s", "3285, 3.285s", "47000, 47s", "800000, '13m 20s'", "3661000, '1h 1m 1s'",
        "7509000, '2h 5m 9s'", "86400000, '1d 0h 0m 0s'", "183845000, '2d 3h 4m 5s'" })
    void formatsCraftingDurationForDiscord(long durationMillis, String expected) {
        assertEquals(expected, DiscordManager.formatDuration(durationMillis));
    }

    @Test
    void durationThresholdFiltersShortCraftingJobs() {
        ConfigBootstrap.discordMinimumCraftingDurationSecondsValue = () -> 300;

        assertFalse(DiscordManager.shouldPostCraftingNotification(299_999L, 1L));
        assertTrue(DiscordManager.shouldPostCraftingNotification(300_000L, 1L));
    }

    @Test
    void amountThresholdFiltersSmallCraftingJobs() {
        ConfigBootstrap.discordMinimumCraftingAmountValue = () -> 1000;

        assertFalse(DiscordManager.shouldPostCraftingNotification(1L, 999L));
        assertTrue(DiscordManager.shouldPostCraftingNotification(1L, 1000L));
    }
}
