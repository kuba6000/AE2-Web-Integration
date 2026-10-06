package pl.kuba6000.ae2webintegration.core.api;

/** Native resource category for terminal filtering; does not change quantity units or identity. */
public enum ResourceType {
    /** An item, including ordinary filled containers. */
    ITEM,
    /** A fluid, including native virtual fluid-drop items. */
    FLUID,
    /** An addon resource outside the item and fluid categories. */
    OTHER
}
