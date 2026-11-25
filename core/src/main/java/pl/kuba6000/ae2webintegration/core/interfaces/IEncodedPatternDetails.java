package pl.kuba6000.ae2webintegration.core.interfaces;

/** Decoded terminal pattern. Read inputs, outputs and kind on the server thread only. */
public interface IEncodedPatternDetails extends IAECraftingPatternDetails {

    /**
     * Condensed primary inputs, counting each ingredient once (not all substitution alternatives).
     * Include its required multiplier in the quantity; preserve zero-quantity catalysts if supported.
     */
    IAEGenericStack[] web$getCondensedInputs();

    boolean web$isCraftingPattern();
}
