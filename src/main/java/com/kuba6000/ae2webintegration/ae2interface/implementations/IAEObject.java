package com.kuba6000.ae2webintegration.ae2interface.implementations;

import java.lang.ref.WeakReference;

import com.kuba6000.ae2webintegration.core.utils.GSONUtils;

public class IAEObject<T> {

    @GSONUtils.SkipGSON
    protected final WeakReference<T> object;

    public IAEObject(T object) {
        this.object = new WeakReference<>(object);
    }

    public boolean isValid() {
        return object.get() != null;
    }

    public T get() {
        return object.get();
    }

    @Override
    public int hashCode() {
        return object.get()
            .hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof IAEObject && object.get()
            .equals(((IAEObject<?>) obj).object.get());
    }
}
