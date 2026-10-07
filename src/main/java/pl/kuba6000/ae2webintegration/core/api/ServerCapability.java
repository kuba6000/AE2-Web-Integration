package pl.kuba6000.ae2webintegration.core.api;

/** Server-wide native features with stable JSON property names. */
public enum ServerCapability {

    CRAFTING_LIGHT_MODE("craftingLightMode"),
    CRAFTING_PLAN_STEPS("craftingPlanSteps");

    private final String wireName;

    ServerCapability(String wireName) {
        this.wireName = wireName;
    }

    /** Property name used when publishing support through the HTTP API. */
    public String wireName() {
        return wireName;
    }
}
