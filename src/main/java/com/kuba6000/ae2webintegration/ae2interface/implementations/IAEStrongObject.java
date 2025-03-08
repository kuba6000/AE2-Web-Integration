package com.kuba6000.ae2webintegration.ae2interface.implementations;

import com.kuba6000.ae2webintegration.core.utils.GSONUtils;

public abstract class IAEStrongObject<T> extends IAEObject<T> {

    @GSONUtils.SkipGSON
    T object;

    public IAEStrongObject(T object) {
        this.object = object;
    }

    @Override
    public boolean isValid() {
        return object != null;
    }

    @Override
    protected boolean isNull() {
        return object == null;
    }

    @Override
    public T get() {
        return object;
    }

    @Override
    public void invalidate() {
        object = null;
    }

    @Override
    public void reUse(T object, Object... args) {
        this.object = object;
    }

    @Override
    public int hashCode() {
        if (object == null) return 0;
        return object.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (object == null) return obj == null;
        return obj instanceof IAEStrongObject && object.equals(((IAEStrongObject<?>) obj).object);
    }

}
