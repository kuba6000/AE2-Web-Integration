package pl.kuba6000.ae2webintegration.core.standingorders;

import java.math.BigInteger;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** An observed stock scaled by positive integer factors. Products can exceed the native long range. */
@Desugar
public record StockRatio(@NotNull StableKey reference, long numerator, long denominator) {

    /** Smallest whole quantity satisfying this ratio. Comparing integer stocks to this ceiling is exact. */
    @NotNull
    BigInteger target(long referenceStock) {
        if (numerator == denominator) return BigInteger.valueOf(referenceStock);
        BigInteger product = BigInteger.valueOf(referenceStock)
            .multiply(BigInteger.valueOf(numerator));
        // Division truncates towards zero, which already rounds negative products up.
        if (product.signum() > 0) product = product.add(BigInteger.valueOf(denominator - 1));
        return product.divide(BigInteger.valueOf(denominator));
    }
}
