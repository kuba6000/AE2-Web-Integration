package pl.kuba6000.ae2webintegration.core.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/** Shared resource metadata; admission and orphan removal run inside the owning transaction. */
public final class ResourceCatalogue {

    private ResourceCatalogue() {}

    public static void upsert(@NotNull Connection db, @NotNull Map<StableKey, ResourceDescriptor> descriptions,
        @NotNull Set<StableKey> referenced) throws SQLException {
        try (PreparedStatement upsert = db.prepareStatement("""
            INSERT INTO history_resources (
                item_key, registry_namespace, registry_path, components, damage,
                display_name, resource_type, base_key
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?
            WHERE ? OR EXISTS (SELECT 1 FROM history_resources WHERE item_key = ?)
            ON CONFLICT(item_key) DO UPDATE SET
                registry_namespace = excluded.registry_namespace,
                registry_path = excluded.registry_path,
                components = excluded.components,
                damage = excluded.damage,
                display_name = excluded.display_name,
                resource_type = excluded.resource_type,
                base_key = COALESCE(excluded.base_key, history_resources.base_key)
            WHERE history_resources.registry_namespace IS NOT excluded.registry_namespace
                OR history_resources.registry_path IS NOT excluded.registry_path
                OR history_resources.components IS NOT excluded.components
                OR history_resources.damage IS NOT excluded.damage
                OR history_resources.display_name IS NOT excluded.display_name
                OR history_resources.resource_type IS NOT excluded.resource_type
                OR history_resources.base_key IS NOT COALESCE(excluded.base_key, history_resources.base_key)
            """)) {
            for (Map.Entry<StableKey, ResourceDescriptor> entry : descriptions.entrySet()) {
                ResourceDescriptor description = entry.getValue();
                upsert.setString(
                    1,
                    entry.getKey()
                        .toString());
                upsert.setString(2, description.registryNamespace());
                upsert.setString(3, description.registryPath());
                upsert.setInt(4, description.componentCount());
                upsert.setInt(5, description.damage());
                upsert.setString(6, description.displayName());
                upsert.setString(
                    7,
                    description.resourceType()
                        .name());
                upsert.setString(
                    8,
                    description.baseKey() == null ? null
                        : description.baseKey()
                            .toString());
                upsert.setBoolean(9, referenced.contains(entry.getKey()));
                upsert.setString(
                    10,
                    entry.getKey()
                        .toString());
                upsert.addBatch();
            }
            upsert.executeBatch();
        }
    }

    public static void removeUnreferenced(@NotNull Connection db, @NotNull Set<Long> resources) throws SQLException {
        try (PreparedStatement delete = db.prepareStatement("""
            DELETE FROM history_resources
            WHERE id = ?
                AND NOT EXISTS (
                    SELECT 1
                    FROM history_stock
                    WHERE resource_id = ?
                )
                AND NOT EXISTS (
                    SELECT 1
                    FROM history_flow
                    WHERE resource_id = ?
                )
                AND NOT EXISTS (SELECT 1 FROM resource_pins WHERE resource_id = ?)
                AND NOT EXISTS (SELECT 1 FROM crafting_resources WHERE resource_id = ?)
            """)) {
            for (Long resourceId : resources) {
                delete.setLong(1, resourceId);
                delete.setLong(2, resourceId);
                delete.setLong(3, resourceId);
                delete.setLong(4, resourceId);
                delete.setLong(5, resourceId);
                delete.addBatch();
            }
            if (!resources.isEmpty()) delete.executeBatch();
        }
    }
}
