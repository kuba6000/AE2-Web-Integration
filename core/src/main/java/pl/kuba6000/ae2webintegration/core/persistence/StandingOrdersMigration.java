package pl.kuba6000.ae2webintegration.core.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.jetbrains.annotations.NotNull;

/** Adds standing orders, their per-grid switch and the standing-order crafting requester source. */
final class StandingOrdersMigration implements DatabaseMigration {

    @Override
    public int fromVersion() {
        return 10;
    }

    @Override
    public int toVersion() {
        return 11;
    }

    @Override
    public void migrate(@NotNull Connection db) throws SQLException {
        try (Statement sql = db.createStatement()) {
            sql.execute(
                "ALTER TABLE grids ADD COLUMN standing_orders_enabled INTEGER NOT NULL DEFAULT 1 CHECK (standing_orders_enabled IN (0, 1))");
            sql.execute(
                """
                    CREATE TABLE standing_orders (
                        order_key TEXT NOT NULL CHECK (typeof(order_key) = 'text' AND length(order_key) <= 22) PRIMARY KEY,
                        grid_id INTEGER NOT NULL CHECK (typeof(grid_id) = 'integer') REFERENCES grids(id),
                        ordinal INTEGER NOT NULL CHECK (typeof(ordinal) = 'integer' AND ordinal BETWEEN 0 AND 2147483647),
                        created_by TEXT NOT NULL CHECK (typeof(created_by) = 'text' AND length(created_by) <= 36),
                        definition TEXT NOT NULL CHECK (typeof(definition) = 'text' AND length(definition) <= 65536),
                        definition_version INTEGER NOT NULL CHECK (typeof(definition_version) = 'integer' AND definition_version >= 1)
                    )
                    """);
            sql.execute(
                """
                    CREATE TABLE standing_order_resources (
                        order_key TEXT NOT NULL CHECK (typeof(order_key) = 'text' AND length(order_key) <= 22) REFERENCES standing_orders(order_key) ON DELETE CASCADE,
                        slot INTEGER NOT NULL CHECK (typeof(slot) = 'integer' AND slot BETWEEN 0 AND 2147483647),
                        resource_id INTEGER NOT NULL CHECK (typeof(resource_id) = 'integer') REFERENCES history_resources(id),
                        PRIMARY KEY (order_key, slot)
                    )
                    """);
            sql.execute("CREATE INDEX standing_orders_grid ON standing_orders(grid_id, ordinal)");
            sql.execute("CREATE INDEX standing_order_resources_resource ON standing_order_resources(resource_id)");
            // SQLite cannot change a CHECK constraint in place, so the table is rebuilt under its final name.
            sql.execute("ALTER TABLE crafting_requesters RENAME TO crafting_requesters_v10");
            sql.execute(
                """
                    CREATE TABLE crafting_requesters (
                        job_id INTEGER NOT NULL REFERENCES crafting_jobs(id) ON DELETE CASCADE,
                        ordinal INTEGER NOT NULL CHECK (typeof(ordinal) = 'integer' AND ordinal BETWEEN 0 AND 2147483647),
                        source TEXT NOT NULL CHECK (source IN ('PLAYER', 'AUTOMATION', 'WEB', 'STANDING_ORDER')),
                        player_uuid TEXT CHECK (player_uuid IS NULL OR (typeof(player_uuid) = 'text' AND length(player_uuid) = 36)),
                        player_name TEXT CHECK (player_name IS NULL OR (typeof(player_name) = 'text' AND length(player_name) <= 1048576)),
                        order_key TEXT CHECK (order_key IS NULL OR (typeof(order_key) = 'text' AND length(order_key) <= 22)),
                        order_name TEXT CHECK (order_name IS NULL OR (typeof(order_name) = 'text' AND length(order_name) <= 128)),
                        CHECK ((source = 'PLAYER' AND player_uuid IS NOT NULL AND player_name IS NOT NULL AND order_key IS NULL AND order_name IS NULL)
                            OR (source = 'STANDING_ORDER' AND order_key IS NOT NULL AND order_name IS NOT NULL AND player_uuid IS NULL AND player_name IS NULL)
                            OR (source IN ('AUTOMATION', 'WEB') AND player_uuid IS NULL AND player_name IS NULL AND order_key IS NULL AND order_name IS NULL)),
                        PRIMARY KEY (job_id, ordinal)
                    )
                    """);
            sql.execute("""
                INSERT INTO crafting_requesters (job_id, ordinal, source, player_uuid, player_name)
                SELECT job_id, ordinal, source, player_uuid, player_name FROM crafting_requesters_v10
                """);
            sql.execute("DROP TABLE crafting_requesters_v10");
            sql.execute(
                "CREATE UNIQUE INDEX crafting_requester_players ON crafting_requesters(job_id, player_uuid) WHERE source = 'PLAYER'");
            sql.execute(
                "CREATE UNIQUE INDEX crafting_requester_sources ON crafting_requesters(job_id, source, COALESCE(order_key, '')) WHERE source <> 'PLAYER'");
        }
    }
}
