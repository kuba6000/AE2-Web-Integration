package pl.kuba6000.ae2webintegration.core.api;

import com.github.bsideup.jabel.Desugar;

/**
 * Native calculation choices for one request. Light mode trades pattern features for faster calculation;
 * ignore missing permits a job to wait for ingredients that arrive later.
 *
 * @param lightMode     use the native light calculator
 * @param ignoreMissing allow the native job to wait for ingredients arriving later
 * @example lightMode false
 * @example ignoreMissing false
 */
@Desugar
public record CraftingOptions(boolean lightMode, boolean ignoreMissing) {}
