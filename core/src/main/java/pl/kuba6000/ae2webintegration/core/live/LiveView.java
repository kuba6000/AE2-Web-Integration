package pl.kuba6000.ae2webintegration.core.live;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

/**
 * Version of a live snapshot or update.
 *
 * @param generation opaque identifier shared by a snapshot and its subsequent updates
 * @param revision   decimal revision within this generation
 * @example generation 1.0
 * @example revision 2
 */
@Desugar
public record LiveView(@NotNull String generation, @NotNull String revision) {}
