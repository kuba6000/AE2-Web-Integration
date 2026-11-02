package pl.kuba6000.ae2webintegration.core.crafting;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceCapture.ContributionKind;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/**
 * Independently worked traces. The numbers are hand-computed so that each fixture is internally consistent:
 * every task's total outputs cover exactly the crafted amounts of the requests it serves.
 */
@SuppressWarnings("PMD.AvoidMagicNumbers")
public final class TraceFixtures {

    public static final int MACHINE = 0;
    public static final int PLATE = 1;
    public static final int GEAR = 2;
    public static final int INGOT = 3;
    public static final int SLAG = 4;
    public static final int LUBRICANT = 5;

    private TraceFixtures() {}

    /**
     * Four machines are requested. One is in storage, three are crafted by task 0 (three crafts; per craft one
     * machine from two gears, two plates and one lubricant). Of the six gears, two come
     * from storage and four from task 1 (three crafts, two gears and one slag per craft, one ingot per craft).
     * Task 1 is shared: a later gear request of three reuses one leftover gear and crafts the other two with it.
     * Of the six plates, one is in storage, two are emitted and three are crafted by task 2 (one gear each).
     * Of the three ingots that task 1 requires, two are missing and one has an unknown source.
     *
     * <pre>
     * R0 Machine 4: c0 STORAGE 1, c1 CRAFT 3 -> T0 (children R1 Gear 6, R2 Plate 6, R3 Lubricant 3)
     * R1 Gear 6:    c2 STORAGE 2, c3 CRAFT 4 -> T1 (child R4 Ingot 3)
     * R2 Plate 6:   c4 STORAGE 1, c5 EMITTED 2, c6 CRAFT 3 -> T2 (child R5 Gear 3)
     * R3 Lubricant 3: c7 STORAGE 3
     * R4 Ingot 3:   c8 MISSING 2, c9 UNKNOWN 1
     * R5 Gear 3:    c10 REUSED 1, c11 CRAFT 2 -> T1 (shared)
     * </pre>
     */
    public static CraftingTraceCapture gears() {
        CraftingTraceCapture.Builder trace = new CraftingTraceCapture.Builder(200, 1000);
        for (String name : new String[] { "Machine", "Plate", "Gear", "Ingot", "Slag", "Lubricant" })
            trace.addResource(() -> descriptor(name));
        int machine = trace.addRequest(MACHINE, 4);
        trace.root(machine, false);
        trace.addContribution(machine, ContributionKind.STORAGE, 1);
        int make = trace.addTask(3);
        trace.addCraft(machine, 3, make);
        trace.addOutput(make, MACHINE, 1, true);
        trace.addInput(make, GEAR, 2);
        trace.addInput(make, PLATE, 2);
        trace.addInput(make, LUBRICANT, 1);
        int gears = trace.addRequest(GEAR, 6);
        trace.addChildRequest(make, gears);
        int plates = trace.addRequest(PLATE, 6);
        trace.addChildRequest(make, plates);
        int lubricant = trace.addRequest(LUBRICANT, 3);
        trace.addChildRequest(make, lubricant);
        trace.finishTask(make);

        trace.addContribution(gears, ContributionKind.STORAGE, 2);
        int cut = trace.addTask(3);
        trace.addCraft(gears, 4, cut);
        trace.addOutput(cut, GEAR, 2, true);
        trace.addOutput(cut, SLAG, 1, false);
        trace.addInput(cut, INGOT, 1);
        int ingots = trace.addRequest(INGOT, 3);
        trace.addChildRequest(cut, ingots);
        trace.finishTask(cut);

        trace.addContribution(plates, ContributionKind.STORAGE, 1);
        trace.addContribution(plates, ContributionKind.EMITTED, 2);
        int press = trace.addTask(3);
        trace.addCraft(plates, 3, press);
        trace.addOutput(press, PLATE, 1, true);
        trace.addInput(press, GEAR, 1);
        int pressed = trace.addRequest(GEAR, 3);
        trace.addChildRequest(press, pressed);
        trace.finishTask(press);

        trace.addContribution(lubricant, ContributionKind.STORAGE, 3);
        trace.addContribution(ingots, ContributionKind.MISSING, 2);
        trace.addContribution(ingots, ContributionKind.UNKNOWN, 1);
        trace.addContribution(pressed, ContributionKind.REUSED, 1);
        trace.addCraft(pressed, 2, cut);
        return trace.build();
    }

    /**
     * The calculation recursed: crafting A needs B, whose craft needs A again. The inner A request is the
     * recursion start request and is served by the same task as the outer one.
     *
     * <pre>
     * R0 A 1: c0 CRAFT 1 -> T0 (child R1 B 1)
     * R1 B 1: c1 CRAFT 1 -> T1 (child R2 A 1)
     * R2 A 1: c2 CRAFT 1 -> T0 (shared; closes the cycle)
     * </pre>
     */
    public static CraftingTraceCapture recursion() {
        CraftingTraceCapture.Builder trace = new CraftingTraceCapture.Builder(50, 1000);
        trace.addResource(() -> descriptor("A"));
        trace.addResource(() -> descriptor("B"));
        int outer = trace.addRequest(0, 1);
        trace.root(outer, true);
        int makeA = trace.addTask(1);
        trace.addCraft(outer, 1, makeA);
        trace.addOutput(makeA, 0, 1, true);
        trace.addInput(makeA, 1, 1);
        int b = trace.addRequest(1, 1);
        trace.addChildRequest(makeA, b);
        trace.finishTask(makeA);
        int makeB = trace.addTask(1);
        trace.addCraft(b, 1, makeB);
        trace.addOutput(makeB, 1, 1, true);
        trace.addInput(makeB, 0, 1);
        int inner = trace.addRequest(0, 1);
        trace.addChildRequest(makeB, inner);
        trace.finishTask(makeB);
        trace.addCraft(inner, 1, makeA);
        return trace.build();
    }

    public static ResourceDescriptor descriptor(String name) {
        return new ResourceDescriptor(null, null, 0, 0, name, ResourceType.ITEM, null, null);
    }
}
