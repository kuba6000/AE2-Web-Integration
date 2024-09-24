package com.kuba6000.ae2webintegration.mixins;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

@SuppressWarnings("unused")
public class MixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOG = LogManager.getLogger("AE2WebIntegration mixins");

    @Override
    public void onLoad(String mixinPackage) {

    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return false;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

    }

    @Override
    public List<String> getMixins() {

        List<String> mixins = new ArrayList<>(
            Arrays.asList(
                "AE2.CraftingGridCacheMixin",
                "AE2.CraftingLinkAccessor",
                "AE2.CraftingCPUClusterMixin",
                "AE2.CraftingCPUClusterAccessor"));

        // byte[] bytes = null;
        // try {
        // bytes = Launch.classLoader.getClassBytes("appeng.me.cluster.implementations.CraftingCPUCluster");
        // } catch (IOException e) {
        // throw new RuntimeException(e);
        // }
        //
        // ClassNode cn = new ClassNode();
        // ClassReader cr = new ClassReader(bytes);
        // cr.accept(cn, 0);
        //
        // if (cn.methods.stream()
        // .anyMatch(m -> m.name.equals("mergeJob"))) {
        // LOG.warn("Job merging detected, completely disabling it!!!!!!!!!!!!!");
        // mixins.addAll(
        // Arrays.asList(
        // "AE2.MergeDisabler.CraftingCPUClusterMixin",
        // "AE2.MergeDisabler.ContainerCraftConfirmMixin",
        // "AE2.MergeDisabler.CraftingGridCacheMixin"));
        // }

        LOG.info("MIXING INTO AE2 LETS GOOOOOOOOOOOOOOOOOOOOOOOOO");

        return mixins;
    }

    @Override
    public void preApply(String s, ClassNode classNode, String s1, IMixinInfo iMixinInfo) {

    }

    @Override
    public void postApply(String s, ClassNode classNode, String s1, IMixinInfo iMixinInfo) {

    }
}
