# Legacy integration drafts

Draft implementations for **post-refactor core** (`IStackList`, unified `IAEStorageGrid`, `web$isFluid()`).

Use these when porting older integration branches after bumping the `core` submodule to the refactored commit.

## Apply checklist

### Shared (both legacy branches)

1. Copy `shared/*.java` → `src/main/java/pl/kuba6000/ae2webintegration/ae2interface/legacy/`
2. Bump `core` submodule to the refactored commit from `1.21.1-core-integration`

### gtnh-native-fluid

1. Replace `AEStorageGridMixin.java` with draft
2. Replace `AEStackMixin.java` with draft (drops `IStack`)
3. Replace `AECraftingJobMixin.java` — remove `populatePlan`, keep `generateSummary`
4. Trim `AEMeInventoryItemMixin` — remove `IStack` overloads when core drops them
5. Update `AEItemListMixin` to implement `IStackList`

### 1.12.2-core-integration

1. Replace `AEStorageGridMixin.java` with draft (adds fluid channel)
2. Replace `AEItemStackMixin.java` with draft
3. **Add** `AEFluidStackMixin.java` (new)
4. Register `AEFluidStackMixin` in `MixinPlugin`
5. Same AEMeInventory / AEItemList / AECraftingJob steps as GTNH
