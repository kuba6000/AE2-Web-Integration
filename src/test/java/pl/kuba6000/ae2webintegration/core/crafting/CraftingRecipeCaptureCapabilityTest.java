package pl.kuba6000.ae2webintegration.core.crafting;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummary;

class CraftingRecipeCaptureCapabilityTest {

    @Test
    void unavailableCaptureDoesNotReadOrReconstructTheFlatSummary() {
        IAECraftingJob job = new IAECraftingJob() {

            @Override
            public boolean web$isSimulation() {
                throw new AssertionError("Capture must not read native state when unsupported");
            }

            @Override
            public long web$getByteTotal() {
                throw new AssertionError("Capture must not read native state when unsupported");
            }

            @Override
            public ICraftingPlanSummary web$generateSummary(IAEGrid grid) {
                throw new AssertionError("Flat totals cannot reconstruct recipe dependencies");
            }
        };

        assertNull(job.web$captureRecipes(0, 0));
    }
}
