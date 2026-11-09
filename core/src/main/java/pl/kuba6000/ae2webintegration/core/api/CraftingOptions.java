package pl.kuba6000.ae2webintegration.core.api;

import com.github.bsideup.jabel.Desugar;

/**
 * Optional crafting calculation modes. Check the server capabilities before enabling either option.
 *
 * @param lightMode     use Lite mode for faster calculation with limited pattern features
 * @param ignoreMissing allow the job to wait for ingredients arriving later
 * @example lightMode false
 * @example ignoreMissing false
 */
@Desugar
public record CraftingOptions(boolean lightMode, boolean ignoreMissing) {}
