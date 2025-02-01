package com.kuba6000.ae2webintegration.ae2interface.implementations;

public class IAEStrongObject<T> {

    T object;

    public IAEStrongObject(T object) {
        this.object = object;
    }

    public boolean isValid() {
        return object != null;
    }

    public T get() {
        return object;
    }

    @Override
    public int hashCode() {
        return object.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof IAEStrongObject && object.equals(((IAEStrongObject<?>) obj).object);
    }

}
