package com.kuba6000.ae2webintegration;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;

import com.mojang.authlib.GameProfile;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IMachineSet;
import appeng.api.networking.crafting.CraftingItemList;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.pathing.ControllerState;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.PlayerSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.hooks.TickHandler;
import appeng.me.Grid;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.parts.reporting.PartTerminal;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.registry.GameRegistry;

public class FMLEventHandler {

    private static IChatComponent lastFakePlayerChatMessage = null;

    private static PlayerSource getPlayerSource() {
        IMachineSet terminals = AE2Controller.activeGrid.getMachines(PartTerminal.class);
        IGridNode node = terminals.iterator()
            .next();
        IActionHost actionHost = (IActionHost) node.getMachine();
        World world = node.getWorld();

        try {
            return new PlayerSource(
                new FakePlayer(
                    (WorldServer) world,
                    new GameProfile(UUID.nameUUIDFromBytes("AE2CONTROLLER".getBytes("UTF-8")), "AE2CONTROLLER")) {

                    @Override
                    public void addChatMessage(IChatComponent message) {
                        lastFakePlayerChatMessage = message;
                    }
                },
                actionHost);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) return;
        ++AE2Controller.timer;
        if (AE2Controller.timer % 5 == 0) {
            while (AE2Controller.requests.peek() != null) {
                AE2Controller.REQUEST_OPERATION request = AE2Controller.requests.poll();
                if (!AE2Controller.isValid()) {
                    AE2Controller.updates.put(request, AE2Controller.INVALID_DATA);
                    return;
                }
                AE2Controller.AE2Data data = new AE2Controller.AE2Data();
                if (request instanceof AE2Controller.LIST_CPUS || request instanceof AE2Controller.GET_CPU) {
                    ICraftingGrid craftingGrid = AE2Controller.activeGrid.getCache(ICraftingGrid.class);
                    int id = 1;
                    for (ICraftingCPU cpu : craftingGrid.getCpus()) {
                        if (cpu instanceof CraftingCPUCluster cluster) {
                            String name = cluster.getName();
                            if (name.isEmpty()) name = "CPU #" + id++;
                            AE2Controller.AE2Data.ClusterData clusterData;
                            data.clusters.put(name, clusterData = new AE2Controller.AE2Data.ClusterData());
                            if (cluster.isBusy()) {
                                clusterData.finalOutput = cluster.getFinalOutput();
                                if (request instanceof AE2Controller.GET_CPU cpu_info
                                    && cpu_info.hashcode == name.hashCode()) {
                                    clusterData.initItemLists();
                                    cluster.getListOfItem(clusterData.active, CraftingItemList.ACTIVE);
                                    cluster.getListOfItem(clusterData.pending, CraftingItemList.PENDING);
                                    cluster.getListOfItem(clusterData.storage, CraftingItemList.STORAGE);
                                }
                            } else {
                                if (request instanceof AE2Controller.GET_CPU cpu_info
                                    && cpu_info.hashcode == name.hashCode()) {
                                    clusterData.initItemLists();
                                    cluster.getListOfItem(clusterData.storage, CraftingItemList.STORAGE);
                                }
                            }
                        }
                    }
                } else if (request instanceof AE2Controller.CANCEL_CPU) {
                    ICraftingGrid craftingGrid = AE2Controller.activeGrid.getCache(ICraftingGrid.class);
                    int id = 1;
                    for (ICraftingCPU cpu : craftingGrid.getCpus()) {
                        if (cpu instanceof CraftingCPUCluster cluster) {
                            String name = cluster.getName();
                            if (name.isEmpty()) name = "CPU #" + id++;
                            if (cpu.isBusy() && ((AE2Controller.CANCEL_CPU) request).hashcode == name.hashCode()) {
                                ((CraftingCPUCluster) cpu).cancel();
                            }
                        }
                    }
                } else if (request instanceof AE2Controller.GET_ITEMS) {
                    IStorageGrid storageGrid = AE2Controller.activeGrid.getCache(IStorageGrid.class);
                    IMEMonitor<IAEItemStack> monitor = storageGrid.getItemInventory();
                    AE2Controller.globalItemList = monitor.getStorageList();
                    AE2Controller.hashcodeToAEItemStack.clear();
                    data.items = new ArrayList<>();
                    for (IAEItemStack iaeItemStack : AE2Controller.globalItemList) {
                        int hash;
                        AE2Controller.hashcodeToAEItemStack.put(hash = iaeItemStack.hashCode(), iaeItemStack);
                        AE2Controller.GSONDetailedItem detailedItem = new AE2Controller.GSONDetailedItem();
                        detailedItem.itemid = GameRegistry.findUniqueIdentifierFor(iaeItemStack.getItem())
                            .toString() + ":"
                            + iaeItemStack.getItemDamage();
                        detailedItem.itemname = iaeItemStack.getItemStack()
                            .getDisplayName();
                        detailedItem.quantity = iaeItemStack.getStackSize();
                        detailedItem.craftable = iaeItemStack.isCraftable();
                        detailedItem.hashcode = hash;
                        data.items.add(detailedItem);
                    }
                } else if (request instanceof AE2Controller.BEGIN_ORDER) {
                    ICraftingGrid craftingGrid = AE2Controller.activeGrid.getCache(ICraftingGrid.class);
                    boolean allBusy = true;
                    for (ICraftingCPU cpu : craftingGrid.getCpus()) {
                        if (!cpu.isBusy()) {
                            allBusy = false;
                            break;
                        }
                    }
                    if (!allBusy) {
                        IStorageGrid storageGrid = AE2Controller.activeGrid.getCache(IStorageGrid.class);
                        final IItemList<IAEItemStack> itemList = storageGrid.getItemInventory()
                            .getStorageList();
                        IAEItemStack realItem = itemList.findPrecise(((AE2Controller.BEGIN_ORDER) request).toOrder);
                        if (realItem != null && realItem.isCraftable()) {

                            PlayerSource source = getPlayerSource();
                            Future<ICraftingJob> job = craftingGrid.beginCraftingJob(
                                source.player.worldObj,
                                AE2Controller.activeGrid,
                                source,
                                ((AE2Controller.BEGIN_ORDER) request).toOrder,
                                null);

                            int jobID = AE2Controller.getNextJobID();
                            AE2Controller.jobs.put(jobID, job);
                            data.jobID = jobID;
                            if (AE2Controller.jobs.size() > 3) {
                                int toDeleteBelowAndEqual = jobID - 3;
                                AE2Controller.jobs.entrySet()
                                    .removeIf(
                                        integerFutureEntry -> integerFutureEntry.getKey() <= toDeleteBelowAndEqual);
                            }
                        }
                    }
                } else if (request instanceof AE2Controller.CHECK_ORDER) {
                    int id = ((AE2Controller.CHECK_ORDER) request).id;
                    Future<ICraftingJob> job = AE2Controller.jobs.get(id);
                    if (job != null) {
                        if (data.jobIsDone = job.isDone()) {
                            try {
                                ICraftingJob craftingJob = job.get();
                                IStorageGrid storageGrid = AE2Controller.activeGrid.getCache(IStorageGrid.class);
                                IMEInventory<IAEItemStack> items = storageGrid.getItemInventory();
                                final BaseActionSource host = getPlayerSource();
                                data.jobData = new AE2Controller.AE2Data.JobData();
                                data.jobData.isSimulating = craftingJob.isSimulation();
                                data.jobData.bytesTotal = craftingJob.getByteTotal();
                                IItemList<IAEItemStack> plan;
                                craftingJob.populatePlan(
                                    plan = AEApi.instance()
                                        .storage()
                                        .createItemList());
                                data.jobData.plan = new ArrayList<>();
                                for (IAEItemStack iaeItemStack : plan) {
                                    AE2Controller.AE2Data.JobData.GSONJobItem jobItem = new AE2Controller.AE2Data.JobData.GSONJobItem();
                                    jobItem.itemid = GameRegistry.findUniqueIdentifierFor(iaeItemStack.getItem())
                                        .toString() + ":"
                                        + iaeItemStack.getItemDamage();
                                    jobItem.itemname = iaeItemStack.getItemStack()
                                        .getDisplayName();
                                    jobItem.requested = iaeItemStack.getCountRequestable();
                                    jobItem.steps = iaeItemStack.getCountRequestableCrafts();
                                    if (data.jobData.isSimulating) {
                                        IAEItemStack toExtract = iaeItemStack.copy();
                                        toExtract.reset();
                                        toExtract.setStackSize(iaeItemStack.getStackSize());
                                        IAEItemStack missing = toExtract.copy();
                                        toExtract = items.extractItems(toExtract, Actionable.SIMULATE, host);
                                        if (toExtract == null) {
                                            toExtract = missing.copy();
                                            toExtract.setStackSize(0);
                                        }
                                        jobItem.stored = toExtract.getStackSize();
                                        jobItem.missing = missing.getStackSize() - toExtract.getStackSize();
                                    } else {
                                        jobItem.stored = iaeItemStack.getStackSize();
                                        jobItem.missing = 0;
                                    }
                                    data.jobData.plan.add(jobItem);
                                }
                                data.jobData.plan.sort((i1, i2) -> {
                                    if (i1.missing > 0 && i2.missing > 0) return Long.compare(i2.missing, i1.missing);
                                    else if (i1.missing > 0 && i2.missing == 0) return -1;
                                    else if (i1.missing == 0 && i2.missing > 0) return 1;
                                    if (i1.requested > 0 && i2.requested > 0) return Long.compare(i2.steps, i1.steps);
                                    else if (i1.requested > 0 && i2.requested == 0) return -1;
                                    else if (i1.requested == 0 && i2.requested > 0) return 1;
                                    return Long.compare(i2.stored, i1.stored);
                                });
                            } catch (InterruptedException | ExecutionException e) {

                            }
                        }
                    }
                } else if (request instanceof AE2Controller.CANCEL_ORDER) {
                    int id = ((AE2Controller.CANCEL_ORDER) request).id;
                    Future<ICraftingJob> job = AE2Controller.jobs.get(id);
                    if (job != null) {
                        job.cancel(true);
                        AE2Controller.jobs.remove(id);
                    }
                } else if (request instanceof AE2Controller.SUBMIT_ORDER) {
                    ICraftingGrid craftingGrid = AE2Controller.activeGrid.getCache(ICraftingGrid.class);
                    int id = ((AE2Controller.SUBMIT_ORDER) request).id;
                    Future<ICraftingJob> job = AE2Controller.jobs.get(id);
                    if (job != null && job.isDone()) {
                        try {
                            lastFakePlayerChatMessage = null;
                            ICraftingJob craftingJob = job.get();
                            ICraftingLink linked = craftingGrid
                                .submitJob(craftingJob, null, null, true, getPlayerSource());
                            if (linked == null) {
                                if (lastFakePlayerChatMessage != null) {
                                    data.jobSubmissionFailureMessage = lastFakePlayerChatMessage
                                        .getUnformattedTextForChat();
                                } else {
                                    data.jobSubmissionFailureMessage = "UNKNOWN";
                                }
                            }
                        } catch (InterruptedException | ExecutionException e) {
                            data.jobSubmissionFailureMessage = "EXCEPTION";
                        }
                    }
                }
                AE2Controller.updates.put(request, data);
            }
        }

        if (AE2Controller.timer % 100 != 0) return;
        if (!AE2Controller.isValid()) {
            for (Grid grid : TickHandler.INSTANCE.getGridList()) {
                IPathingGrid pathingGrid = grid.getCache(IPathingGrid.class);
                if (pathingGrid != null && !pathingGrid.isNetworkBooting()
                    && pathingGrid.getControllerState() == ControllerState.CONTROLLER_ONLINE) {
                    ICraftingGrid craftingGrid = grid.getCache(ICraftingGrid.class);
                    if (craftingGrid != null) {
                        if ((long) craftingGrid.getCpus()
                            .size() > 5) {
                            AE2Controller.activeGrid = grid;
                        }
                    }
                }
            }
        }
    }

}
