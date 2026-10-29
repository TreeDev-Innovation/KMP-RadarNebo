package com.gusak.lab.radarnebo.ui;

import javax.swing.JComponent;
import java.lang.reflect.Proxy;
import java.util.function.DoubleConsumer;

/**
 * Bridges the macOS trackpad pinch (magnify) gesture, which AWT only exposes through the JDK-internal
 * {@code com.apple.eawt.event} package, to plain callbacks. Compose Desktop has no pinch support.
 *
 * The package only exists in macOS JDKs, so it is reached by reflection: the app still compiles and runs elsewhere.
 * Needs {@code --add-exports java.desktop/com.apple.eawt.event=ALL-UNNAMED} at run time.
 */
public final class MacTrackpad {
    private static final String EVENTS = "com.apple.eawt.event.";

    private MacTrackpad() {
    }

    /**
     * Calls {@code onMagnify} with each pinch delta (e.g. 0.02 = 2% bigger) for gestures over
     * {@code component} or its children. Returns false when unsupported (non-macOS JDK).
     */
    public static boolean installPinch(JComponent component, DoubleConsumer onMagnify) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) return false;
        try {
            Class<?> listenerType = Class.forName(EVENTS + "MagnificationListener");
            Class<?> eventType = Class.forName(EVENTS + "MagnificationEvent");
            Class<?> gestureListenerType = Class.forName(EVENTS + "GestureListener");
            Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType},
                (proxy, method, args) -> {
                    if (method.getName().equals("magnify")) {
                        Object event = args[0];
                        onMagnify.accept((Double) eventType.getMethod("getMagnification").invoke(event));
                        eventType.getMethod("consume").invoke(event);
                        return null;
                    }
                    // Object methods (hashCode, equals, toString) asked of the listener. No switch expression:
                    // this module compiles Java at source level 11.
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == args[0];
                    return "MacTrackpad pinch listener";
                });
            Class.forName(EVENTS + "GestureUtilities")
                .getMethod("addGestureListenerTo", JComponent.class, gestureListenerType)
                .invoke(null, component, listener);
            return true;
        } catch (Throwable unsupported) {
            // Usually IllegalAccessException: the JVM was started without the --add-exports flag above.
            System.err.println("Trackpad pinch-to-zoom unavailable: " + unsupported
                + " (start the JVM with --add-exports java.desktop/com.apple.eawt.event=ALL-UNNAMED)");
            return false;
        }
    }
}
