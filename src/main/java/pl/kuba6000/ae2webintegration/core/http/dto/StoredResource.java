package pl.kuba6000.ae2webintegration.core.http.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;

/**
 * A stored or craftable resource with its currently available web identity.
 *
 * @param registryNamespace native registry namespace, or null when unavailable
 * @param componentCount    root NBT entry count on 1.7.10/1.12.2/1.20.1; effective component count on 1.21.1; null when
 *                          unsupported
 * @param damage            raw legacy item damage/metadata or modern damage value; null for fluids and unsupported
 *                          resources
 * @param registryPath      native registry path without namespace or damage, or null when unavailable
 * @param displayName       resource display name
 * @param resourceType      native resource category for item and fluid filtering
 * @param quantity          number of resource units
 * @param craftable         whether the resource is craftable on this grid
 * @param itemKey           stable resource key, or null when an identity could not be captured
 * @param identityStatus    identity failure code AMBIGUOUS, UNSUPPORTED or UNAVAILABLE; null when the resource key is
 *                          available
 * @param icon              atlas location within the response's page table; null when icons were not requested or
 *                          unavailable
 * @example registryPath iron_ingot
 * @example displayName Iron Ingot
 * @example resourceType ITEM
 * @example quantity 64
 * @example craftable true
 * @example itemKey AAAAAAAAAAAAAAAAAAAAAA
 * @example identityStatus null
 */
@Desugar
public record StoredResource(@Nullable String registryNamespace, @Nullable String registryPath,
    @NotNull String displayName, @Nullable Integer componentCount, @Nullable Integer damage,
    @NotNull ResourceType resourceType, long quantity, boolean craftable, @Nullable String itemKey,
    @Nullable String identityStatus, @Nullable IconMappings.Reference icon) {}
