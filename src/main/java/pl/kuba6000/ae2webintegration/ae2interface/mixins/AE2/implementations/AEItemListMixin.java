package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import java.util.Iterator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

@Mixin(value = KeyCounter.class, remap = false)
public abstract class AEItemListMixin implements IStackList {

    @Shadow
    public abstract long get(AEKey key);

    @Override
    public long web$getAmount(IAEKey key) {
        return get((AEKey) key);
    }

    @Override
    public Iterable<IAEGenericStack> web$stacks() {
        KeyCounter self = (KeyCounter) (Object) this;
        return () -> new Iterator<>() {

            private final Iterator<Object2LongMap.Entry<AEKey>> delegate = self.iterator();

            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public IAEGenericStack next() {
                Object2LongMap.Entry<AEKey> entry = delegate.next();
                return (IAEGenericStack) (Object) new GenericStack(entry.getKey(), entry.getLongValue());
            }
        };
    }
}
