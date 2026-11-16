package pl.kuba6000.ae2webintegration.core.standingorders;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/**
 * What a standing order crafts once its trigger holds.
 *
 * @param resource resource to craft
 * @param amount   how much to order per submission
 * @param options  calculation options; each must be supported by the server
 * @param cpu      crafting CPU selection
 */
@Desugar
public record CraftAction(@NotNull StableKey resource, @NotNull AmountPolicy amount, @NotNull CraftingOptions options,
    @NotNull CpuTarget cpu) {}
