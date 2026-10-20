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
            MERGE INTO history_resources r
            USING (VALUES (
                CAST(? AS VARCHAR(22)),
                CAST(? AS VARCHAR(4096)),
                CAST(? AS VARCHAR(16384)),
                CAST(? AS INTEGER),
                CAST(? AS INTEGER),
                CAST(? AS VARCHAR(1048576)),
                CAST(? AS VARCHAR(16)),
                CAST(? AS VARCHAR(22)),
                CAST(? AS BOOLEAN)
            )) AS incoming (
                item_key, registry_namespace, registry_path, components, damage,
                display_name, resource_type, base_key, referenced
            )
            ON r.item_key = incoming.item_key
            WHEN MATCHED AND (
                r.registry_namespace IS DISTINCT FROM incoming.registry_namespace
                OR r.registry_path IS DISTINCT FROM incoming.registry_path
                OR r.components IS DISTINCT FROM incoming.components
                OR r.damage IS DISTINCT FROM incoming.damage
                OR r.display_name IS DISTINCT FROM incoming.display_name
                OR r.resource_type IS DISTINCT FROM incoming.resource_type
                OR r.base_key IS DISTINCT FROM COALESCE(incoming.base_key, r.base_key)
            ) THEN UPDATE SET
                registry_namespace = incoming.registry_namespace,
                registry_path = incoming.registry_path,
                components = incoming.components,
                damage = incoming.damage,
                display_name = incoming.display_name,
                resource_type = incoming.resource_type,
                base_key = COALESCE(incoming.base_key, r.base_key)
            WHEN NOT MATCHED AND incoming.referenced THEN INSERT (
                item_key, registry_namespace, registry_path, components, damage,
                display_name, resource_type, base_key
            ) VALUES (
                incoming.item_key, incoming.registry_namespace, incoming.registry_path,
                incoming.components, incoming.damage, incoming.display_name,
                incoming.resource_type, incoming.base_key
            )
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
            """)) {
            for (Long resourceId : resources) {
                delete.setLong(1, resourceId);
                delete.setLong(2, resourceId);
                delete.setLong(3, resourceId);
                delete.setLong(4, resourceId);
                delete.addBatch();
            }
            if (!resources.isEmpty()) delete.executeBatch();
        }
    }
}
