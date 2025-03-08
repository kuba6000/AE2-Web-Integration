package com.kuba6000.ae2webintegration.ae2interface.implementations;

import java.lang.ref.WeakReference;

import com.kuba6000.ae2webintegration.core.utils.GSONUtils;

public abstract class IAEWeakObject<T> extends IAEObject<T> {

    @GSONUtils.SkipGSON
    protected WeakReference<T> object;

    public IAEWeakObject(T object) {
        this.object = new WeakReference<>(object);
    }

    @Override
    public boolean isValid() {
        if (object == null) return false;
        return object.get() != null;
    }

    @Override
    protected boolean isNull() {
        return object == null;
    }

    @Override
    public T get() {
        return object.get();
    }

    @Override
    public void invalidate() {
        if (object == null) return;
        object.clear();
        object.enqueue();
        object = null;
    }

    @Override
    public void reUse(T object, Object... args) {
        if (this.object != null) {
            this.object.clear();
            this.object.enqueue();
        }
        this.object = new WeakReference<>(object);
    }

    @Override
    public int hashCode() {
        if (object == null) return 0;
        return object.get()
            .hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (object == null) return obj == null;
        return obj instanceof IAEWeakObject && object.get()
            .equals(((IAEWeakObject<?>) obj).object.get());
    }
}
