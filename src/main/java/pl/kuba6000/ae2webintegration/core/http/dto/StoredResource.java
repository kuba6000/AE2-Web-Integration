package pl.kuba6000.ae2webintegration.core.http.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.icons.IconMappings;

/**
 * A stored or craftable resource with its currently available web identity.
 *
 * @param itemId         registry resource identifier
 * @param itemName       resource display name
 * @param quantity       number of resource units
 * @param craftable      whether the resource is craftable on this grid
 * @param itemKey        stable resource key, or null when an identity could not be captured
 * @param identityStatus identity failure code AMBIGUOUS, UNSUPPORTED or UNAVAILABLE; null when the resource key is
 *                       available
 * @param icon           atlas location within the response's page table; null when icons were not requested or
 *                       unavailable
 * @example itemId minecraft:iron_ingot
 * @example itemName Iron Ingot
 * @example quantity 64
 * @example craftable true
 * @example itemKey AAAAAAAAAAAAAAAAAAAAAA
 * @example identityStatus null
 */
@Desugar
public record StoredResource(@NotNull String itemId, @NotNull String itemName, long quantity, boolean craftable,
    @Nullable String itemKey, @Nullable String identityStatus, @Nullable IconMappings.Reference icon) {}
