package pl.kuba6000.ae2webintegration.core.persistence;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

import org.jetbrains.annotations.NotNull;

/** Version-driven upgrades using the world's existing startup transaction. */
final class DatabaseMigrations {

    private static final List<DatabaseMigration> MIGRATIONS = Collections.singletonList(new HomePinGroupsMigration());

    private DatabaseMigrations() {}

    /** Read-only preflight rejects unsupported versions and missing migration paths before journal changes. */
    static int sourceVersion(@NotNull Connection db) throws SQLException, IOException {
        try (Statement sql = db.createStatement();
            ResultSet marker = sql.executeQuery("SELECT type FROM sqlite_master WHERE name = 'world_schema'")) {
            if (!marker.next()) {
                try (Statement objects = db.createStatement();
                    ResultSet rows = objects
                        .executeQuery("SELECT 1 FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' LIMIT 1")) {
                    if (rows.next()) throw new IOException("World database schema is unrecognized");
                    return 0;
                }
            }
            if (!"table".equals(marker.getString(1)))
                throw new IOException("World database schema metadata is not a table");
        }
        long version;
        try (Statement sql = db.createStatement(); ResultSet rows = sql.executeQuery("""
            SELECT singleton, version, typeof(singleton) = 'integer' AND typeof(version) = 'integer'
            FROM world_schema
            """)) {
            if (!rows.next() || rows.getLong(1) != 1 || !rows.getBoolean(3))
                throw new IOException("Unsupported world database schema metadata");
            version = rows.getLong(2);
            if (rows.next()) throw new IOException("Unsupported world database schema metadata");
        }
        if (version < 1 || version > WorldSchema.VERSION)
            throw new IOException("Unsupported world database schema version: " + version);
        int source = (int) version;
        for (int step = source; step != WorldSchema.VERSION;) step = migrationFrom(step).toVersion();
        return source;
    }

    private static DatabaseMigration migrationFrom(int version) throws IOException {
        DatabaseMigration selected = null;
        for (DatabaseMigration migration : MIGRATIONS) {
            if (migration.fromVersion() != version) continue;
            if (selected != null || migration.toVersion() <= version || migration.toVersion() > WorldSchema.VERSION)
                throw new IOException("Invalid world database migration registration");
            selected = migration;
        }
        if (selected == null) throw new IOException("No world database migration from version " + version);
        return selected;
    }

    static void initialize(@NotNull Connection db) throws SQLException, IOException {
        int source = sourceVersion(db);
        if (source == 0) WorldSchema.create(db);
        else {
            while (source != WorldSchema.VERSION) {
                DatabaseMigration migration = migrationFrom(source);
                migration.migrate(db);
                try (PreparedStatement update = db
                    .prepareStatement("UPDATE world_schema SET version = ? WHERE singleton = 1 AND version = ?")) {
                    update.setInt(1, migration.toVersion());
                    update.setInt(2, source);
                    if (update.executeUpdate() != 1)
                        throw new IOException("World database schema changed during upgrade");
                }
                source = migration.toVersion();
            }
        }
        WorldSchema.requireTables(db);
    }
}
