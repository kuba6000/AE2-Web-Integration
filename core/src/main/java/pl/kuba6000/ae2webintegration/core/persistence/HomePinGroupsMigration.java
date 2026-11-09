package pl.kuba6000.ae2webintegration.core.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.jetbrains.annotations.NotNull;

final class HomePinGroupsMigration implements DatabaseMigration {

    static final String GROUP_INDEX = "CREATE INDEX resource_pins_home_group ON resource_pins(home_group_key)";

    @Override
    public int fromVersion() {
        return 9;
    }

    @Override
    public int toVersion() {
        return 10;
    }

    @Override
    public void migrate(@NotNull Connection db) throws SQLException {
        try (Statement sql = db.createStatement()) {
            createGroupTable(sql);
            sql.execute(
                """
                    ALTER TABLE resource_pins ADD COLUMN home_group_key TEXT REFERENCES home_pin_groups(group_key)
                        CHECK (home_group_key IS NULL OR (typeof(home_group_key) = 'text' AND length(home_group_key) <= 22 AND home_order IS NOT NULL))
                    """);
            sql.execute(GROUP_INDEX);
        }
    }

    static void createGroupTable(@NotNull Statement sql) throws SQLException {
        sql.execute(
            """
                CREATE TABLE home_pin_groups (
                    group_key TEXT NOT NULL CHECK (typeof(group_key) = 'text' AND length(group_key) <= 22) PRIMARY KEY,
                    owner TEXT NOT NULL CHECK (typeof(owner) = 'text' AND length(owner) <= 36),
                    name TEXT NOT NULL CHECK (typeof(name) = 'text' AND length(name) BETWEEN 1 AND 128 AND name = trim(name)),
                    ordinal INTEGER NOT NULL CHECK (typeof(ordinal) = 'integer' AND ordinal BETWEEN 0 AND 2147483647),
                    UNIQUE (owner, name)
                )
                """);
    }
}
