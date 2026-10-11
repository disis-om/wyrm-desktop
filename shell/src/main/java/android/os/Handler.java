package android.os;

import java.util.IdentityHashMap;
import java.util.Map;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Wyrm Desktop: Handler on the main looper, as Swing timers (UI thread). */
public class Handler {
    private final Map<Runnable, Timer> pending = new IdentityHashMap<>();

    public Handler(Looper looper) {}

    public boolean post(Runnable r) {
        SwingUtilities.invokeLater(r);
        return true;
    }

    public boolean postDelayed(Runnable r, long delayMillis) {
        synchronized (pending) {
            Timer timer = new Timer((int) Math.max(0, delayMillis), e -> {
                synchronized (pending) { pending.remove(r); }
                r.run();
            });
            timer.setRepeats(false);
            Timer old = pending.put(r, timer);
            if (old != null) old.stop();
            timer.start();
        }
        return true;
    }

    public void removeCallbacks(Runnable r) {
        synchronized (pending) {
            Timer timer = pending.remove(r);
            if (timer != null) timer.stop();
        }
    }
}
