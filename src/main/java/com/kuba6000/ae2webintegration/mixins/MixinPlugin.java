package com.kuba6000.ae2webintegration.mixins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import net.minecraft.launchwrapper.Launch;

import org.spongepowered.asm.lib.ClassReader;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

@SuppressWarnings("unused")
public class MixinPlugin implements IMixinConfigPlugin {

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

        /*
         * URL res = Launch.classLoader.getResource("appeng/me/cluster/implementations/CraftingCPUCluster.class");
         * byte[] bytes;
         * try (InputStream is = res.openStream()) {
         * bytes = is.readAllBytes();
         * } catch (IOException e) {
         * throw new RuntimeException(e);
         * }
         */

        byte[] bytes = null;
        try {
            bytes = Launch.classLoader.getClassBytes("appeng.me.cluster.implementations.CraftingCPUCluster");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        ClassNode cn = new ClassNode();
        ClassReader cr = new ClassReader(bytes);
        cr.accept(cn, 0);

        if (cn.methods.stream()
            .anyMatch(m -> m.name.equals("mergeJob"))) mixins.add("AE2.JobMergingDisablerMixin");

        return mixins;
    }

    @Override
    public void preApply(String s, ClassNode classNode, String s1, IMixinInfo iMixinInfo) {

    }

    @Override
    public void postApply(String s, ClassNode classNode, String s1, IMixinInfo iMixinInfo) {

    }
}
