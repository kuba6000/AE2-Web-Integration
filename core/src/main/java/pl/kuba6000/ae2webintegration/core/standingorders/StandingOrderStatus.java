package pl.kuba6000.ae2webintegration.core.standingorders;

/**
 * Where a standing order currently is. The engine publishes one status per order together with a
 * {@link StandingOrderReason} where the status alone does not explain the cause.
 */
public enum StandingOrderStatus {

    /** Evaluated; the trigger does not hold, or there is nothing to order. */
    IDLE,
    /** A crafting calculation is running, or its finished plan is about to be checked again and submitted. */
    CALCULATING,
    /** The job runs on a crafting CPU. */
    CRAFTING,
    /**
     * Like {@link #CRAFTING}, for longer than {@code long_running_job_minutes}. Informational; the engine never
     * stops the job.
     */
    CRAFTING_LONG,
    /** The last job finished and the cooldown has not passed yet. */
    COOLDOWN,
    /** The calculation needs ingredients the network lacks; retried with backoff. */
    BLOCKED_MISSING,
    /**
     * Calculating or submitting failed, or the engine failed unexpectedly; retried with backoff. The reason tells
     * which.
     */
    SUBMIT_FAILED,
    /**
     * The order cannot run: unknown, ambiguous, not craftable or emitter-backed resource, unsupported option,
     * invalid stored definition. The reason tells which.
     */
    UNAVAILABLE,
    /** The order itself is disabled. */
    DISABLED,
    /** Standing orders are switched off for the whole server or for this network. */
    SWITCHED_OFF,
    /** The network is not loaded, is booting or has no online controller. Nothing is lost while it is away. */
    GRID_UNAVAILABLE,
    /** No usable stock read yet: a read is pending, the last one failed, or a newer one is needed. */
    WAITING_FOR_STOCK,
    /** The trigger holds but no eligible idle crafting CPU is available. */
    WAITING_FOR_CPU,
    /** The trigger holds but the network already runs its allowed number of calculations. */
    QUEUED
}
