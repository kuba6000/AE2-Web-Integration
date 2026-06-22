package pl.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.api.JSON_DetailedItem;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

public class GetItems extends ISyncedRequest {

    @Override
    boolean init(Map<String, String> getParams) {
        return true;
    }

    @Override
    void handle(IAEGrid grid) {
        if (grid == null) {
            deny("GRID_NOT_FOUND");
            return;
        }
        IAEStorageGrid storageGrid = grid.web$getStorageGrid();
        IAECraftingGrid craftingGrid = grid.web$getCraftingGrid();
        IStackList storageList = storageGrid.web$getStorageList();
        AE2Controller.hashcodeToStack.clear();
        ArrayList<JSON_DetailedItem> items = new ArrayList<>();
        Set<IAEKey> listed = new HashSet<>();

        for (IAEGenericStack stack : storageList.web$stacks()) {
            addItem(items, stack, grid);
            listed.add(stack.web$what());
        }

        for (IAEKey craftable : craftingGrid.web$getCraftables(null)) {
            if (containsSameType(listed, craftable)) {
                continue;
            }
            addItem(items, AE2Controller.AE2Interface.web$stackOf(craftable, 0), grid);
        }

        setData(items);
        done();
    }

    private static void addItem(ArrayList<JSON_DetailedItem> items, IAEGenericStack stack, IAEGrid grid) {
        IAEKey key = stack.web$what();

        int hash = stack.hashCode();
        AE2Controller.hashcodeToStack.put(hash, stack);

        JSON_DetailedItem detailedItem = new JSON_DetailedItem();
        detailedItem.itemid = key.web$getItemID();
        detailedItem.itemname = key.web$getDisplayName();
        detailedItem.quantity = stack.web$amount();
        detailedItem.craftable = key.web$isCraftable(grid);
        detailedItem.hashcode = hash;

        items.add(detailedItem);
    }

    private static boolean containsSameType(Set<IAEKey> keys, IAEKey key) {
        for (IAEKey listed : keys) {
            if (key.web$isSameType(listed)) {
                return true;
            }
        }
        return false;
    }

}
