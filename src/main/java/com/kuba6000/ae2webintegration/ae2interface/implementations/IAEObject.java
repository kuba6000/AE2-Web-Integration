package com.kuba6000.ae2webintegration.ae2interface.implementations;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;

public abstract class IAEObject<T> {

    private static final HashMap<Class<? extends IAEObject<?>>, ArrayList<? extends IAEObject<?>>> objectPool = new HashMap<>();

    public static <T, U extends IAEObject<T>> U create(Class<U> clazz, T object, Object... args) {
        if (object == null) return null;
        ArrayList<IAEObject<T>> pooledObjects = (ArrayList<IAEObject<T>>) objectPool.get(clazz);
        if (pooledObjects == null) {
            pooledObjects = new ArrayList<>();
            objectPool.put(clazz, pooledObjects);
        } else {
            for (IAEObject<T> pooledObject : pooledObjects) {
                if (pooledObject.isNull()) {
                    pooledObject.reUse(object, args);
                    return (U) pooledObject;
                }
            }
        }
        IAEObject<T> newObject = null;
        try {
            Class<?>[] classes = new Class[args.length + 1];
            classes[0] = object.getClass();
            for (int i = 0; i < args.length; i++) {
                classes[i+1] = args[i].getClass();
            }
            Object[] newArgs = new Object[args.length + 1];
            newArgs[0] = object;
            if (args.length > 0) System.arraycopy(args, 0, newArgs, 1, args.length);
            out: for (Constructor<?> declaredConstructor : clazz.getDeclaredConstructors()) {
                if (declaredConstructor.getParameterCount() == newArgs.length) {
                    Class<?>[] parameterTypes = declaredConstructor.getParameterTypes();
                    for (int i = 0; i < parameterTypes.length; i++) {
                        Class<?> parameterType = parameterTypes[i];
                        if (!parameterType.isAssignableFrom(classes[i])) {
                            continue out;
                        }
                    }
                    newObject = (IAEObject<T>) declaredConstructor.newInstance(newArgs);
                    break;
                }
            }
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
        pooledObjects.add(newObject);
        return (U) newObject;
    }

    public static void invalidateAll() {
        for (ArrayList<? extends IAEObject<?>> pooledObjects : objectPool.values()) {
            for (IAEObject<?> pooledObject : pooledObjects) {
                pooledObject.invalidate();
            }
        }
    }

    public abstract boolean isValid();

    protected abstract boolean isNull();

    public abstract T get();

    public abstract void invalidate();

    public abstract void reUse(T object, Object... args);

}
