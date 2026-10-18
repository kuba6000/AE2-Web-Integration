package pl.kuba6000.ae2webintegration.core;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/** Independent save lifecycle for tests that use native-grid snapshots. */
abstract class GridTestScope {

    final TestGridFixtures.Worlds worlds = new TestGridFixtures.Worlds();

    @TempDir
    File gridSave;

    @BeforeEach
    void initializeGridSave() throws IOException {
        worlds.initialize(gridSave);
    }

    @AfterEach
    void clearGridSave() {
        CoreEngine.GRID_IDENTITIES.clear();
        try {
            worlds.close();
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }
}
