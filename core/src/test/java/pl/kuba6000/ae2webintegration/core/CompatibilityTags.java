package pl.kuba6000.ae2webintegration.core;

/**
 * JUnit tags selecting the tests rerun by the library compatibility tasks in core/build.gradle.
 * Tag only tests whose result can depend on that library's version; the default test task runs everything.
 */
public final class CompatibilityTags {

    /** Assertions depend on JSON written or parsed by Gson (2.2.4 and 2.8.0 on legacy Minecraft). */
    public static final String GSON = "gson";
    /** Assertions depend on parsing or writing the TOML configuration through NightConfig. */
    public static final String NIGHT_CONFIG = "nightconfig";
    /** Assertions depend on the world database and therefore on the SQLite JDBC driver. */
    public static final String SQLITE = "sqlite";

    private CompatibilityTags() {}
}
