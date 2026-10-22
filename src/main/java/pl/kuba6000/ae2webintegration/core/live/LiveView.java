package pl.kuba6000.ae2webintegration.core.live;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

/** Identifies the exact state a client holds: a snapshot of this generation and epoch continues at the revision. */
@Desugar
public record LiveView(@NotNull String generation, @NotNull String revision) {}
