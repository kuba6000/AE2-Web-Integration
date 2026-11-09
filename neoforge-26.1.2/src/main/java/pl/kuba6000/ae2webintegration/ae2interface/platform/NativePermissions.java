package pl.kuba6000.ae2webintegration.ae2interface.platform;

import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;

import org.jetbrains.annotations.NotNull;

public final class NativePermissions {

    private NativePermissions() {}

    public static boolean hasLevel(@NotNull PermissionSet permissions, int level) {
        // byId clamps its input; levels above the vanilla maximum must remain denied.
        return level <= 0 || (level <= PermissionLevel.OWNERS.id()
            && permissions.hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(level))));
    }
}
