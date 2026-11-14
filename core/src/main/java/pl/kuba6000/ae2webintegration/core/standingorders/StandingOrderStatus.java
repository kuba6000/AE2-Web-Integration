package pl.kuba6000.ae2webintegration.core.standingorders;

/**
 * Where a standing order currently is. The engine publishes one status per order together with an English detail
 * message where the status alone does not explain the cause.
 */
public enum StandingOrderStatus {

    /** Evaluated; the trigger does not hold, or there is nothing to order. */
    IDLE,
    /** A crafting calculation is running. */
    CALCULATING,
    /** The calculation finished and the plan is about to be checked again and submitted. */
    SUBMIT,
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
     * Calculating or submitting failed, or the engine failed unexpectedly; retried with backoff. The message tells why.
     */
    SUBMIT_FAILED,
    /**
     * The order cannot run: unknown, not craftable or emitter-backed resource, unsupported option, invalid stored
     * definition.
     */
    UNAVAILABLE,
    /** The order itself is disabled. */
    DISABLED,
    /** Standing orders are switched off for the whole server or for this network. */
    SWITCHED_OFF,
    /** The network is not loaded, is booting or has no online controller. */
    GRID_UNAVAILABLE,
    /** No usable stock read yet: first read pending, last read failed, or a read after the previous job is needed. */
    WAITING_FOR_STOCK,
    /** The trigger holds but no eligible idle crafting CPU is available. */
    WAITING_FOR_CPU,
    /** The trigger holds but the network already runs its allowed number of calculations. */
    QUEUED
}
