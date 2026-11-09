package pl.kuba6000.ae2webintegration.ae2interface.accessors;

import org.jetbrains.annotations.NotNull;

import appeng.me.GridNode;

/** Adapter-local permission updates, called only on the server thread. */
public interface IGridPermissions {

    void web$updateNodePermissions(@NotNull GridNode node);
}
