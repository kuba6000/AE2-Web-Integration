package pl.kuba6000.ae2webintegration.core.config;

/** Test-only isolation for the process-wide account store. */
public final class CoreDataTestFixture {

    private CoreDataTestFixture() {}

    public static void reset() {
        CoreData.instance = new CoreData();
    }
}
