package com.gusak.lab.radarnebo.desktop;

import com.apple.eawt.event.GestureUtilities;
import com.apple.eawt.event.MagnificationEvent;
import com.apple.eawt.event.MagnificationListener;

import javax.swing.JComponent;
import java.util.function.DoubleConsumer;

/**
 * Bridges the macOS trackpad pinch (magnify) gesture, which AWT only exposes through the JDK-internal
 * {@code com.apple.eawt.event} package, to plain callbacks. Compose Desktop has no pinch support.
 *
 * Needs {@code --add-exports java.desktop/com.apple.eawt.event=ALL-UNNAMED} at compile and run time.
 */
public final class MacTrackpad {
    private MacTrackpad() {
    }

    /**
     * Calls {@code onMagnify} with each pinch delta (e.g. 0.02 = 2% bigger) for gestures over
     * {@code component} or its children. Returns false when unsupported (non-macOS JDK).
     */
    public static boolean installPinch(JComponent component, DoubleConsumer onMagnify) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) return false;
        try {
            GestureUtilities.addGestureListenerTo(component, new MagnificationListener() {
                @Override
                public void magnify(MagnificationEvent event) {
                    onMagnify.accept(event.getMagnification());
                    event.consume();
                }
            });
            return true;
        } catch (Throwable unsupported) {
            // Usually IllegalAccessError: the JVM was started without the --add-exports flag above.
            System.err.println("Trackpad pinch-to-zoom unavailable: " + unsupported
                + " (start the JVM with --add-exports java.desktop/com.apple.eawt.event=ALL-UNNAMED)");
            return false;
        }
    }
}
