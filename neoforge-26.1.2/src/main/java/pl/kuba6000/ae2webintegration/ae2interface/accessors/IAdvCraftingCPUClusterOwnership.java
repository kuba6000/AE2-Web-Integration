package pl.kuba6000.ae2webintegration.ae2interface.accessors;

import java.util.UUID;

import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Native virtual CPU membership, including replacement of the anonymous free-capacity CPU. */
public interface IAdvCraftingCPUClusterOwnership {

    boolean web$ownsCpu(@Nullable UUID id, @NotNull AdvCraftingCPU cpu);
}
