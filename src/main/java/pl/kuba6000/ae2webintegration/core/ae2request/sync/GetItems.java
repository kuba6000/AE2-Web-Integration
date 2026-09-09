package pl.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.api.JSON_DetailedItem;
import pl.kuba6000.ae2webintegration.core.identity.ItemIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

public class GetItems extends ISyncedRequest {

    @Override
    void handle(IAEGrid grid) {
        if (grid == null) {
            deny("GRID_NOT_FOUND");
            return;
        }
        IAEStorageGrid storageGrid = grid.web$getStorageGrid();
        IAECraftingGrid craftingGrid = grid.web$getCraftingGrid();
        IStackList storageList = storageGrid.web$getStorageList();
        ArrayList<JSON_DetailedItem> items = new ArrayList<>();
        Set<IAEKey> listed = new HashSet<>();
        ItemIdentityRegistry.Listing listing = AE2Controller.itemIdentities.beginListing(grid);

        for (IAEGenericStack stack : storageList.web$stacks()) {
            addItem(items, stack, grid, listing);
            listed.add(stack.web$what());
        }

        for (IAEKey craftable : craftingGrid.web$getCraftables(null)) {
            if (!listed.add(craftable)) {
                continue;
            }
            addItem(items, AE2Controller.AE2Interface.web$stackOf(craftable, 0), grid, listing);
        }

        listing.commit();
        succeed(items);
    }

    private static void addItem(ArrayList<JSON_DetailedItem> items, IAEGenericStack stack, IAEGrid grid,
        ItemIdentityRegistry.Listing listing) {
        IAEKey key = stack.web$what();

        JSON_DetailedItem detailedItem = new JSON_DetailedItem();
        detailedItem.itemid = key.web$getItemID();
        detailedItem.itemname = key.web$getDisplayName();
        detailedItem.quantity = stack.web$amount();
        detailedItem.craftable = key.web$isCraftable(grid);

        try {
            StableKey identity = listing.remember(key);
            detailedItem.itemKey = identity.toString();

        } catch (ItemIdentityRegistry.Ambiguous e) {
            detailedItem.identityStatus = "AMBIGUOUS";
        } catch (UnsupportedOperationException e) {
            detailedItem.identityStatus = "UNSUPPORTED";
        } catch (RuntimeException e) {
            detailedItem.identityStatus = "UNAVAILABLE";
        }

        items.add(detailedItem);
    }

}
