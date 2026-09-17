package pl.kuba6000.ae2webintegration.core.http;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

/**
 * An unsuccessful API response.
 *
 * @param status machine-readable error code
 * @param data   always {@code null} for these errors
 * @example status GRID_NOT_FOUND
 * @example data null
 */
@Desugar
public record ErrorResponse(@NotNull ApiStatus status, @Nullable Void data) {}
