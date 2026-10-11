package android.view;

/**
 * Wyrm Desktop: the shared screens ask the view for a haptic tick. A computer
 * has no vibration motor, so the tick is a no-op.
 */
public class View {
    public boolean performHapticFeedback(int feedbackConstant) {
        return false;
    }
}
