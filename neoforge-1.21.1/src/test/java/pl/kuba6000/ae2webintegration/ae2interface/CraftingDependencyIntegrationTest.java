package pl.kuba6000.ae2webintegration.ae2interface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.MockedStatic;

import com.google.common.collect.ImmutableList;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.crafting.CraftingCalculation;
import appeng.hooks.ticking.TickHandler;
import appeng.me.helpers.BaseActionSource;
import appeng.util.Platform;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingDependencyGraph;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture.SummaryKind;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/** Real AE2 calculator and loader-transformed capture; only the network environment is controlled. */
@Execution(ExecutionMode.SAME_THREAD)
class CraftingDependencyIntegrationTest {

    @Test
    void chainKeepsNativeBatchRoundingAndDeclaredDependencies() throws Exception {
        AEItemKey log = AEItemKey.of(Items.OAK_LOG);
        AEItemKey plank = AEItemKey.of(Items.OAK_PLANKS);
        AEItemKey stick = AEItemKey.of(Items.STICK);
        Environment environment = new Environment();
        IPatternDetails planks = environment.recipe(new GenericStack(plank, 4), new GenericStack(log, 1));
        IPatternDetails sticks = environment.recipe(new GenericStack(stick, 4), new GenericStack(plank, 2));
        environment.stock.add(log, 1);

        ICraftingPlan nativePlan = environment.calculate(new GenericStack(stick, 5));
        assertFalse(nativePlan.simulation());
        assertEquals(
            1L,
            nativePlan.patternTimes()
                .get(planks));
        assertEquals(
            2L,
            nativePlan.patternTimes()
                .get(sticks));
        CraftingDependencyGraph graph = capture(nativePlan, "chain-rounding");
        CraftingRecipeCapture data = graph.capture();
        assertEquals(
            5L,
            graph.root()
                .amount());
        int stickRecipe = producer(
            graph,
            graph.root()
                .resourceId());
        assertEquals(
            2L,
            data.recipes()
                .get(stickRecipe)
                .crafts());
        int plankResource = ingredientResource(graph, stickRecipe);
        int plankRecipe = producer(graph, plankResource);
        assertEquals(
            1L,
            data.recipes()
                .get(plankRecipe)
                .crafts());
        assertEquals(
            4L,
            data.outputs()
                .get(
                    graph.outputs(stickRecipe)
                        .get(0))
                .amount());
        int logResource = ingredientResource(graph, plankRecipe);
        assertEquals(1L, summary(data, SummaryKind.INITIAL, logResource));
        assertTrue(
            graph.declaredProducers(logResource)
                .isEmpty());
        assertTrue(
            graph.unplacedRecipes()
                .isEmpty());
        assertEquals(
            2,
            data.recipes()
                .size());
    }

    @Test
    void sharedIntermediateRetainsStockSummaryWithoutAllocatingItToConsumers() throws Exception {
        AEItemKey ore = AEItemKey.of(Items.RAW_IRON);
        AEItemKey ingot = AEItemKey.of(Items.IRON_INGOT);
        AEItemKey plate = AEItemKey.of(Items.IRON_TRAPDOOR);
        AEItemKey rod = AEItemKey.of(Items.IRON_NUGGET);
        AEItemKey machine = AEItemKey.of(Items.HOPPER);
        Environment environment = new Environment();
        IPatternDetails smelting = environment.recipe(new GenericStack(ingot, 2), new GenericStack(ore, 1));
        environment.recipe(new GenericStack(plate, 1), new GenericStack(ingot, 1));
        environment.recipe(new GenericStack(rod, 2), new GenericStack(ingot, 1));
        environment.recipe(new GenericStack(machine, 1), new GenericStack(plate, 1), new GenericStack(rod, 2));
        environment.stock.add(ingot, 1);
        environment.stock.add(ore, 2);

        ICraftingPlan nativePlan = environment.calculate(new GenericStack(machine, 2));
        assertFalse(nativePlan.simulation());
        assertEquals(
            2L,
            nativePlan.patternTimes()
                .get(smelting));
        CraftingDependencyGraph graph = capture(nativePlan, "shared-partly-stocked");
        CraftingRecipeCapture data = graph.capture();
        int rootRecipe = producer(
            graph,
            graph.root()
                .resourceId());
        List<Integer> rootInputs = graph.ingredients(rootRecipe);
        assertEquals(2, rootInputs.size());
        int firstIntermediate = data.candidates()
            .get(
                graph.alternatives(rootInputs.get(0))
                    .get(0))
            .resourceId();
        int secondIntermediate = data.candidates()
            .get(
                graph.alternatives(rootInputs.get(1))
                    .get(0))
            .resourceId();
        int sharedA = ingredientResource(graph, producer(graph, firstIntermediate));
        int sharedB = ingredientResource(graph, producer(graph, secondIntermediate));
        assertEquals(sharedA, sharedB);
        assertEquals(1L, summary(data, SummaryKind.INITIAL, sharedA));
        int smeltingRecipe = producer(graph, sharedA);
        assertEquals(
            2L,
            data.recipes()
                .get(smeltingRecipe)
                .crafts());
        assertEquals(2L, summary(data, SummaryKind.INITIAL, ingredientResource(graph, smeltingRecipe)));
        assertEquals(
            4,
            data.recipes()
                .size());
        assertTrue(
            graph.unplacedRecipes()
                .isEmpty());
        assertTrue(
            data.summaries()
                .stream()
                .noneMatch(row -> row.kind() == SummaryKind.MISSING));
    }

    private static CraftingDependencyGraph capture(ICraftingPlan nativePlan, String scenario) {
        // These casts prove the production loader mixins participate; no fake core identity is supplied.
        assertTrue((Object) nativePlan instanceof IAECraftingJob);
        assertTrue(
            (Object) nativePlan.finalOutput()
                .what() instanceof IAEKey);
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        try (MockedStatic<ServerLifecycleHooks> lifecycle = mockStatic(ServerLifecycleHooks.class)) {
            lifecycle.when(ServerLifecycleHooks::getCurrentServer)
                .thenReturn(server);
            long started = System.nanoTime();
            CraftingRecipeCapture capture = ((IAECraftingJob) (Object) nativePlan).web$captureRecipes(1000, 10000);
            long captured = System.nanoTime();
            CraftingDependencyGraph graph = CraftingDependencyGraph.from(capture);
            long built = System.nanoTime();
            assertTrue(
                capture.limits()
                    .isEmpty());
            assertTrue(
                capture.resources()
                    .stream()
                    .allMatch(resource -> resource.itemKey() != null));
            assertTrue(
                capture.recipes()
                    .stream()
                    .allMatch(CraftingRecipeCapture.Recipe::complete));
            System.out.printf(
                "native-capture scenario=%s resources=%d recipes=%d candidates=%d outputs=%d captureNs=%d graphNs=%d%n",
                scenario,
                capture.resources()
                    .size(),
                capture.recipes()
                    .size(),
                capture.candidates()
                    .size(),
                capture.outputs()
                    .size(),
                captured - started,
                built - captured);
            return graph;
        }
    }

    private static int producer(CraftingDependencyGraph graph, int resourceId) {
        List<Integer> outputs = graph.declaredProducers(resourceId);
        assertEquals(1, outputs.size());
        return graph.capture()
            .outputs()
            .get(outputs.get(0))
            .recipeId();
    }

    private static int ingredientResource(CraftingDependencyGraph graph, int recipeId) {
        List<Integer> ingredients = graph.ingredients(recipeId);
        assertEquals(1, ingredients.size());
        List<Integer> alternatives = graph.alternatives(ingredients.get(0));
        assertEquals(1, alternatives.size());
        return graph.capture()
            .candidates()
            .get(alternatives.get(0))
            .resourceId();
    }

    private static long summary(CraftingRecipeCapture capture, SummaryKind kind, int resourceId) {
        return capture.summaries()
            .stream()
            .filter(row -> row.kind() == kind && row.resourceId() == resourceId)
            .mapToLong(CraftingRecipeCapture.Summary::amount)
            .sum();
    }

    private static final class Environment {

        private final KeyCounter stock = new KeyCounter();
        private final Map<AEKey, List<IPatternDetails>> recipes = new HashMap<>();

        private IPatternDetails recipe(GenericStack output, GenericStack... inputs) {
            IPatternDetails pattern = new IPatternDetails() {

                @Override
                public AEItemKey getDefinition() {
                    return AEItemKey.of(Items.PAPER);
                }

                @Override
                public List<GenericStack> getOutputs() {
                    return List.of(output);
                }

                @Override
                public IInput[] getInputs() {
                    IInput[] result = new IInput[inputs.length];
                    for (int index = 0; index < inputs.length; index++) {
                        GenericStack input = inputs[index];
                        result[index] = new IInput() {

                            @Override
                            public GenericStack[] getPossibleInputs() {
                                return new GenericStack[] { input };
                            }

                            @Override
                            public long getMultiplier() {
                                return 1;
                            }

                            @Override
                            public boolean isValid(AEKey key, Level level) {
                                return input.what()
                                    .equals(key);
                            }

                            @Override
                            public AEKey getRemainingKey(AEKey key) {
                                return null;
                            }
                        };
                    }
                    return result;
                }
            };
            recipes.computeIfAbsent(output.what(), ignored -> new ArrayList<>())
                .add(pattern);
            return pattern;
        }

        private ICraftingPlan calculate(GenericStack requested) throws Exception {
            IGrid grid = mock(IGrid.class);
            IGridNode node = mock(IGridNode.class);
            ICraftingService crafting = mock(ICraftingService.class);
            IStorageService storage = mock(IStorageService.class);
            when(grid.getCraftingService()).thenReturn(crafting);
            when(grid.getStorageService()).thenReturn(storage);
            when(node.getGrid()).thenReturn(grid);
            when(crafting.getCraftingFor(any()))
                .thenAnswer(call -> ImmutableList.copyOf(recipes.getOrDefault(call.getArgument(0), List.of())));
            MEStorage inventory = new MEStorage() {

                @Override
                public void getAvailableStacks(KeyCounter result) {
                    result.addAll(stock);
                }

                @Override
                public Component getDescription() {
                    return Component.empty();
                }

                @Override
                public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
                    assertEquals(Actionable.SIMULATE, mode);
                    return Math.min(amount, stock.get(what));
                }
            };
            when(storage.getInventory()).thenReturn(inventory);
            when(storage.getCachedInventory()).thenReturn(stock);
            ICraftingSimulationRequester requester = new ICraftingSimulationRequester() {

                @Override
                public IActionSource getActionSource() {
                    return new BaseActionSource();
                }

                @Override
                public IGridNode getGridNode() {
                    return node;
                }
            };
            CraftingCalculation calculation = new CraftingCalculation(
                mock(Level.class),
                grid,
                requester,
                requested,
                CalculationStrategy.REPORT_MISSING_ITEMS);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try (AutoCloseable cleanup = () -> {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
                ThreadGroup previousServerGroup = Platform.serverThreadGroup;
                try {
                    Platform.serverThreadGroup = Thread.currentThread()
                        .getThreadGroup();
                    TickHandler.instance()
                        .shutdown();
                } finally {
                    Platform.serverThreadGroup = previousServerGroup;
                }
            }) {
                Future<ICraftingPlan> result = executor.submit(calculation::run);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!result.isDone() && System.nanoTime() < deadline) {
                    calculation.simulateFor(1000);
                }
                return result.get(10, TimeUnit.SECONDS);
            }
        }
    }
}
