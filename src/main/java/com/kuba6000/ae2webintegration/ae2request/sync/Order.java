package com.kuba6000.ae2webintegration.ae2request.sync;

import static com.kuba6000.ae2webintegration.AE2Controller.hashcodeToAEItemStack;

import java.util.Map;
import java.util.concurrent.Future;

import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;

import com.google.gson.JsonObject;
import com.kuba6000.ae2webintegration.AE2Controller;

import appeng.api.AEApi;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IMachineSet;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.me.Grid;
import appeng.me.helpers.PlayerSource;
import appeng.parts.reporting.AbstractPartTerminal;

public class Order extends ISyncedRequest {

    private static Class<? extends IGridHost> lastUsedMachineClass = null;

    static PlayerSource getPlayerSource() {
        IMachineSet terminals = null;
        if (lastUsedMachineClass != null) terminals = AE2Controller.activeGrid.getMachines(lastUsedMachineClass);
        if (lastUsedMachineClass == null || terminals.isEmpty()) {
            lastUsedMachineClass = null;
            Iterable<Class<? extends IGridHost>> machines = AE2Controller.activeGrid.getMachineClasses();
            for (Class<? extends IGridHost> machine : machines) {
                if (AbstractPartTerminal.class.isAssignableFrom(machine)
                    && !(terminals = AE2Controller.activeGrid.getMachines(machine)).isEmpty()) {
                    lastUsedMachineClass = machine;
                    break;
                }
            }
        }
        if (lastUsedMachineClass == null || terminals.isEmpty()) {
            throw new RuntimeException("There is no terminal in the AE system");
        }
        IGridNode node = terminals.iterator()
            .next();
        IActionHost actionHost = (IActionHost) node.getMachine();
        World world = node.getWorld();

        return new PlayerSource(new FakePlayer((WorldServer) world, AE2Controller.AEControllerProfile) {

            @Override
            public void sendStatusMessage(ITextComponent chatComponent, boolean actionBar) {
                // no implementation
            }

            @Override
            public void sendMessage(ITextComponent component) {
                Job.lastFakePlayerChatMessage = component;
            }
        }, actionHost);
    }

    private IAEItemStack item;

    @Override
    public boolean init(Map<String, String> getParams) {
        if (!getParams.containsKey("item") || !getParams.containsKey("quantity")) {
            noParam("item", "quantity");
            return false;
        }
        int hash = Integer.parseInt(getParams.get("item"));
        int quantity = Integer.parseInt(getParams.get("quantity"));
        this.item = hashcodeToAEItemStack.get(hash);
        if (this.item == null || !this.item.isCraftable()) {
            deny("ITEM_NOT_FOUND");
            return false;
        }
        this.item = this.item.copy();
        this.item.setStackSize(quantity);
        return true;
    }

    @Override
    public void handle(Grid grid) {
        ICraftingGrid craftingGrid = grid.getCache(ICraftingGrid.class);
        boolean allBusy = true;
        for (ICraftingCPU cpu : craftingGrid.getCpus()) {
            if (!cpu.isBusy()) {
                allBusy = false;
                break;
            }
        }
        if (!allBusy) {
            IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
            final IItemList<IAEItemStack> itemList = storageGrid.getInventory(
                AEApi.instance()
                    .storage()
                    .getStorageChannel(IItemStorageChannel.class))
                .getStorageList();
            IAEItemStack realItem = itemList.findPrecise(this.item);
            if (realItem != null && realItem.isCraftable()) {
                PlayerSource source = getPlayerSource();
                Future<ICraftingJob> job = craftingGrid.beginCraftingJob(
                    source.player()
                        .get().world,
                    AE2Controller.activeGrid,
                    source,
                    this.item,
                    null);

                int jobID = AE2Controller.getNextJobID();
                AE2Controller.jobs.put(jobID, job);
                JsonObject jobData = new JsonObject();
                jobData.addProperty("jobID", jobID);
                if (AE2Controller.jobs.size() > 3) {
                    int toDeleteBelowAndEqual = jobID - 3;
                    AE2Controller.jobs.entrySet()
                        .removeIf(integerFutureEntry -> integerFutureEntry.getKey() <= toDeleteBelowAndEqual);
                }
                setData(jobData);
                done();
            } else {
                deny("ITEM_NOT_FOUND");
            }
        } else {
            deny("ALL_CPU_BUSY");
        }
    }

}
