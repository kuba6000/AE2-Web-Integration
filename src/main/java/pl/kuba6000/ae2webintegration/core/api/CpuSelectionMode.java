package pl.kuba6000.ae2webintegration.core.api;

/** Native policy for automatic CPU selection, independent of current job admission or permissions. */
public enum CpuSelectionMode {

    /** Automatic selection for player requests. */
    PLAYER_ONLY,
    /** Automatic selection for automation requests. */
    AUTOMATION_ONLY,
    /** Automatic selection for either source. */
    ALL
}
