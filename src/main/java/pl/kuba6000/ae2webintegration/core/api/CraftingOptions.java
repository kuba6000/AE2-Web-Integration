package pl.kuba6000.ae2webintegration.core.api;

import com.github.bsideup.jabel.Desugar;

/** Native calculation choices for one request; light mode trades pattern features for faster calculation. */
@Desugar
public record CraftingOptions(boolean lightMode) {}
