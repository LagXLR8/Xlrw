package com.nnpg.alice.premium;

import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.listeners.IListener;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for all ALICE payload modules.
 *
 * Problem: Premium module classes are loaded by a custom ClassLoader ("L"),
 * but Orbit's EventBus uses LambdaMetafactory which requires the caller class
 * to be in the same classloader hierarchy. Java 21 rejects cross-classloader
 * lambda creation with "Invalid caller" errors.
 *
 * Solution: This base class intercepts onActivate/onDeactivate, binds each
 * handler once as a MethodHandle and subscribes a small listener directly.
 * The @EventHandler annotations are replaced with @AliceHandler so Orbit does
 * not try to create a cross-classloader lambda on every activation.
 */
public abstract class AliceModule extends Module {

    private final List<IListener> managedListeners = new ArrayList<>();

    public AliceModule(Category category, String name, String description) {
        super(category, name, description);
    }

    @Override
    public void onActivate() {
        subscribeHandlers();
    }

    @Override
    public void onDeactivate() {
        unsubscribeHandlers();
    }

    /**
     * Scans this class (and superclasses up to AliceModule) for methods
     * annotated with @AliceHandler, then creates bound IListener instances and
     * subscribes them to Meteor's EVENT_BUS.
     */
    private void subscribeHandlers() {
        unsubscribeHandlers(); // clear any stale listeners

        Class<?> klass = this.getClass();
        while (klass != null && klass != AliceModule.class && klass != Module.class) {
            for (Method method : klass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(AliceHandler.class)
                        && method.getParameterCount() == 1
                        && method.getReturnType() == void.class) {

                    Class<?> eventType = method.getParameterTypes()[0];
                    int priority = method.getAnnotation(AliceHandler.class).priority();
                    final MethodHandle handler;
                    try {
                        handler = MethodHandles.privateLookupIn(klass, MethodHandles.lookup())
                            .unreflect(method)
                            .bindTo(this)
                            .asType(MethodType.methodType(void.class, Object.class));
                    } catch (IllegalAccessException exception) {
                        throw new IllegalStateException(
                            "Unable to bind ALICE event handler " + method,
                            exception
                        );
                    }

                    IListener listener = new IListener() {
                        @Override
                        public void call(Object event) {
                            try {
                                handler.invokeExact(event);
                            } catch (Throwable throwable) {
                                if (throwable instanceof Error error) throw error;
                                throwable.printStackTrace();
                            }
                        }

                        @Override
                        public Class<?> getTarget() {
                            return eventType;
                        }

                        @Override
                        public int getPriority() {
                            return priority;
                        }

                        @Override
                        public boolean isStatic() {
                            return false;
                        }
                    };

                    managedListeners.add(listener);
                    meteordevelopment.meteorclient.MeteorClient.EVENT_BUS.subscribe(listener);
                }
            }
            klass = klass.getSuperclass();
        }
    }

    private void unsubscribeHandlers() {
        for (IListener listener : managedListeners) {
            meteordevelopment.meteorclient.MeteorClient.EVENT_BUS.unsubscribe(listener);
        }
        managedListeners.clear();
    }
}
