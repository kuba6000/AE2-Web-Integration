package pl.kuba6000.ae2webintegration.ae2interface.accessors;

import org.jetbrains.annotations.NotNull;

import appeng.me.GridNode;

/** Server-thread native events that maintain the grid's live web grants. */
public interface IGridPermissions {

    void web$ownerChanged(@NotNull GridNode node);

    void web$securityChanged();
}
