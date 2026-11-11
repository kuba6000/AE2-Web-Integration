package pl.kuba6000.ae2webintegration.core.persistence;

import java.sql.Connection;
import java.sql.SQLException;

import org.jetbrains.annotations.NotNull;

/** One registered forward upgrade; the world database owns its connection and transaction. */
interface DatabaseMigration {

    int fromVersion();

    int toVersion();

    void migrate(@NotNull Connection db) throws SQLException;
}
