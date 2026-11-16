package pl.kuba6000.ae2webintegration.core.standingorders;

/**
 * Why a standing order is in its current status, where the status alone does not say. The codes are stable and carry
 * no text, so clients choose the wording and the language; {@link StandingOrderSnapshot} adds the typed parameters
 * some of them need.
 */
public enum StandingOrderReason {

    // WAITING_FOR_STOCK

    /** No complete stock read of the network is available yet: the first one is pending. */
    WAITING_FIRST_READ,
    /** The last stock read of the network failed. */
    READ_FAILED,
    /** The previous job just left its CPU; the next order needs a stock read taken after that. */
    WAITING_FRESH_READ_AFTER_JOB,
    /** The latest stock read does not include a resource the order refers to; another read was requested. */
    WAITING_READ_FOR_NEW_RESOURCES,

    // BLOCKED_MISSING

    /** The calculation needs ingredients the network lacks. */
    MISSING_INGREDIENTS,

    // SUBMIT_FAILED

    /** The crafting calculation failed; the details are in the server log. */
    CALCULATION_FAILED,
    /** The calculation was cancelled after exceeding {@code standing_orders.max_calculation_minutes}. */
    CALCULATION_TIMED_OUT,
    /** The crafting system refused the job; the native message is in the snapshot's submitError. */
    SUBMIT_REJECTED,
    /** Submitting the job failed unexpectedly; the details are in the server log. */
    SUBMIT_ERROR,
    /** The engine failed unexpectedly while evaluating the order; the details are in the server log. */
    UNEXPECTED_ERROR,

    // UNAVAILABLE

    /** The stored definition cannot be read; the order's invalid member says why. */
    INVALID_DEFINITION,
    /** The order uses a crafting option the server does not support; the snapshot's path names it. */
    CAPABILITY_UNAVAILABLE,
    /** The crafted resource is not known on this network. */
    UNKNOWN_RESOURCE,
    /** A resource of the order cannot be told apart from another on this network; the snapshot names it. */
    AMBIGUOUS_RESOURCE,
    /** The crafted resource has no pattern at the moment. */
    NOT_CRAFTABLE,
    /** A level emitter provides the crafted resource, so it cannot be ordered. */
    EMITTER_BACKED,
    /** The amount to order cannot be computed from the stored amount without overflowing. */
    AMOUNT_OVERFLOW,

    // IDLE

    /** The trigger holds, but the stock already reaches the target. */
    NOTHING_TO_ORDER,

    // WAITING_FOR_CPU

    /** No idle crafting CPU accepts standing orders. */
    NO_IDLE_CPU,
    /** The selected crafting CPU is busy, missing, or does not accept standing orders. */
    SELECTED_CPU_UNAVAILABLE,

    // QUEUED

    /** The network already runs the allowed number of calculations. */
    CALCULATION_SLOTS_FULL
}
