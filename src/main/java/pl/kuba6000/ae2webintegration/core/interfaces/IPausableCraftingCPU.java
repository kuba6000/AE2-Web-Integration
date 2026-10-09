package pl.kuba6000.ae2webintegration.core.interfaces;

/** Optional native support for suspending the current job's scheduling; accessed on the server thread. */
public interface IPausableCraftingCPU extends ICraftingCPUCluster {

    boolean web$isPaused();

    /** Sets the desired state without interrupting work already handed to external machines. */
    void web$setPaused(boolean paused);
}
