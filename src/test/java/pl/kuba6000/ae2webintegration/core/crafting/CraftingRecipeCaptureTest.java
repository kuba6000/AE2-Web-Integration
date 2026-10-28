package pl.kuba6000.ae2webintegration.core.crafting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class CraftingRecipeCaptureTest {

    @Test
    void preservesDeclaredAlternativesAndPlanTotalsWithoutAssigningConsumption() {
        CraftingRecipeCapture.Builder capture = new CraftingRecipeCapture.Builder(30, 1000);
        int product = capture.addResource(() -> resource("product"));
        int water = capture.addResource(() -> resource("water"));
        int bucket = capture.addResource(() -> resource("bucket"));
        capture.result(product, 4, false);
        int recipe = capture.addRecipe(4);
        int input = capture.addIngredient(recipe, 2);
        capture.addCandidate(input, water, 1000);
        capture.addCandidate(input, bucket, 1);
        capture.addOutput(recipe, product, 1, true);
        capture.addSummary(CraftingRecipeCapture.SummaryKind.INITIAL, bucket, 1);
        capture.addSummary(CraftingRecipeCapture.SummaryKind.MISSING, water, 3000);
        CraftingRecipeCapture result = capture.build();
        assertEquals(
            4,
            result.result()
                .amount());
        assertEquals(
            4,
            result.recipes()
                .get(0)
                .crafts());
        assertEquals(
            2,
            result.ingredients()
                .get(0)
                .multiplier());
        assertEquals(
            1000,
            result.candidates()
                .get(0)
                .amount());
        assertEquals(
            1,
            result.candidates()
                .get(1)
                .amount());
        assertEquals(
            CraftingRecipeCapture.SummaryKind.INITIAL,
            result.summaries()
                .get(0)
                .kind());
        assertEquals(
            1,
            result.summaries()
                .get(0)
                .amount());
        assertEquals(
            3000,
            result.summaries()
                .get(1)
                .amount());
        assertTrue(
            result.limits()
                .isEmpty());
    }

    @Test
    void entryLimitStopsBeforeReadingAnotherNativeDescription() {
        CraftingRecipeCapture.Builder capture = new CraftingRecipeCapture.Builder(1, 100);
        assertEquals(0, capture.addResource(() -> resource("same")));
        assertTrue(
            capture.build()
                .limits()
                .isEmpty());
        assertEquals(-1, capture.addResource(() -> { throw new AssertionError("native read after cap"); }));
        assertEquals(
            1,
            capture.build()
                .resources()
                .size());
        assertTrue(
            capture.build()
                .limits()
                .contains(CraftingRecipeCapture.Limit.ENTRIES));
    }

    @Test
    void descriptionLimitRejectsOversizedResourceWithoutTruncatingItsIdentity() {
        CraftingRecipeCapture.Builder capture = new CraftingRecipeCapture.Builder(20, 4);
        assertEquals(0, capture.addResource(() -> resource("same")));
        assertEquals(-1, capture.addResource(() -> resource("oversized")));
        assertEquals(
            1,
            capture.build()
                .resources()
                .size());
        assertTrue(
            capture.build()
                .limits()
                .contains(CraftingRecipeCapture.Limit.DESCRIPTION_CHARACTERS));
    }

    @Test
    void completedRecipesAndByproductsRemainDistinctFromPartialDeclarations() {
        CraftingRecipeCapture.Builder capture = new CraftingRecipeCapture.Builder(20, 100);
        int item = capture.addResource(() -> resource("item"));
        int recipe = capture.addRecipe(Long.MAX_VALUE);
        capture.addOutput(recipe, item, 2, true);
        capture.addOutput(recipe, item, 1, false);
        assertFalse(
            capture.build()
                .recipes()
                .get(0)
                .complete());
        capture.finishRecipe(recipe);
        assertTrue(
            capture.build()
                .recipes()
                .get(0)
                .complete());
        assertTrue(
            capture.build()
                .outputs()
                .get(0)
                .primary());
        assertFalse(
            capture.build()
                .outputs()
                .get(1)
                .primary());
        assertEquals(
            Long.MAX_VALUE,
            capture.build()
                .recipes()
                .get(0)
                .crafts());
        assertEquals(
            2,
            capture.build()
                .outputs()
                .get(0)
                .amount());
        assertThrows(IllegalStateException.class, () -> capture.addIngredient(recipe, 1));
    }

    @Test
    void rejectsDanglingResourceReferencesAndKeepsUnkeyedResourcesSeparate() {
        CraftingRecipeCapture.Builder capture = new CraftingRecipeCapture.Builder(20, 100);
        int first = capture.addResource(() -> resource("same"));
        int second = capture.addResource(() -> resource("same"));
        assertNotEquals(first, second);
        assertThrows(IndexOutOfBoundsException.class, () -> capture.result(12, 1, false));
        assertThrows(
            IndexOutOfBoundsException.class,
            () -> capture.addSummary(CraftingRecipeCapture.SummaryKind.EMITTED, 12, 1));
        int recipe = capture.addRecipe(1);
        assertThrows(IndexOutOfBoundsException.class, () -> capture.addOutput(recipe, 12, 1, true));
        int ingredient = capture.addIngredient(recipe, 1);
        assertThrows(IndexOutOfBoundsException.class, () -> capture.addCandidate(ingredient, 12, 1));
    }

    @Test
    void immutableCaptureOwnsItsTablesEvenWhenConstructedDirectly() {
        List<ResourceDescriptor> resources = new ArrayList<>();
        resources.add(resource("original"));
        CraftingRecipeCapture snapshot = new CraftingRecipeCapture(
            null,
            false,
            resources,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptySet());
        resources.clear();
        assertEquals(
            1,
            snapshot.resources()
                .size());
        assertThrows(
            UnsupportedOperationException.class,
            () -> snapshot.resources()
                .clear());
    }

    @Test
    void refusesToCompleteARecipeAfterCaptureWasTruncated() {
        CraftingRecipeCapture.Builder capture = new CraftingRecipeCapture.Builder(1, 100);
        int recipe = capture.addRecipe(1);
        assertEquals(-1, capture.addIngredient(recipe, 1));
        assertThrows(IllegalStateException.class, () -> capture.finishRecipe(recipe));
        assertFalse(
            capture.build()
                .recipes()
                .get(0)
                .complete());
    }

    @Test
    void adapterCanReportKnownOmissionBeforeCallingAnExpensiveGetter() {
        CraftingRecipeCapture.Builder capture = new CraftingRecipeCapture.Builder(1, 100);
        capture.addRecipe(1);
        assertFalse(capture.hasCapacity());
        assertTrue(
            capture.build()
                .limits()
                .isEmpty());
        capture.stopAtEntryLimit();
        assertTrue(
            capture.build()
                .limits()
                .contains(CraftingRecipeCapture.Limit.ENTRIES));
        assertEquals(-1, capture.addResource(() -> { throw new AssertionError("capture stopped"); }));
    }

    @Test
    void cannotSupplyInvalidCaptureBudgets() {
        assertThrows(IllegalArgumentException.class, () -> new CraftingRecipeCapture.Builder(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> new CraftingRecipeCapture.Builder(10, -1));
    }

    private static ResourceDescriptor resource(String name) {
        return new ResourceDescriptor(null, null, 0, 0, name, ResourceType.ITEM, null, null);
    }
}
