package com.mekromn.betterpicker;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

public final class BetterPickerAccessibilityService extends AccessibilityService {
    private static final int IDLE = 0;
    private static final int FIND_ENTRY = 1;
    private static final int FIND_SORT_ENTRY = 2;
    private static final int FIND_MODIFIED = 3;
    private static final int APPLIED = 4;
    private static final int ABORTED = 5;

    // We only touch the picker right as it launches. If DocumentsUI is not ready in this
    // window, stand down for the entire picker session instead of surprising the user later.
    private static final long STARTUP_ACTION_WINDOW_MS = 1400;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private int phase = IDLE;
    private int generation;
    private int retryAttempt;
    private boolean retryScheduled;
    private long sessionStartedMs;
    private int exitMisses;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        resetSession();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null || !isDocumentsUi(pkgCs.toString())) return;

        if (phase == IDLE) {
            beginSession();
        }

        // APPLIED and ABORTED are hard latches. Once either state is reached, this service
        // performs zero more clicks until DocumentsUI actually leaves the screen.
        if (phase == APPLIED || phase == ABORTED) return;

        // Real accessibility events are always the fastest signal. Retry timers are only a
        // fallback for UI transitions that fail to emit the expected event.
        tryAdvance();
    }

    @Override
    public void onInterrupt() {
        resetSession();
    }

    private void beginSession() {
        generation++;
        phase = FIND_ENTRY;
        retryAttempt = 0;
        retryScheduled = false;
        sessionStartedMs = SystemClock.uptimeMillis();
        exitMisses = 0;
        handler.removeCallbacksAndMessages(null);
        tryAdvance();
    }

    private boolean startupWindowExpired() {
        return SystemClock.uptimeMillis() - sessionStartedMs > STARTUP_ACTION_WINDOW_MS;
    }

    private void tryAdvance() {
        if (phase == IDLE || phase == APPLIED || phase == ABORTED) return;

        if (startupWindowExpired()) {
            abortForSession();
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            scheduleRetry();
            return;
        }

        // If DocumentsUI already remembered our desired setting, do absolutely nothing.
        // Current AOSP exposes this as the accessibility label "Sorted by ...".
        if (isAlreadyModifiedNewest(root)) {
            markApplied(false);
            return;
        }

        if (phase == FIND_ENTRY) {
            // Old/AOSP path: Sort by is directly exposed.
            AccessibilityNodeInfo directSort = firstById(root,
                    "com.google.android.documentsui:id/menu_sort",
                    "com.android.documentsui:id/menu_sort",
                    "com.google.android.documentsui:id/option_menu_sort",
                    "com.android.documentsui:id/option_menu_sort");
            if (directSort == null) {
                directSort = findExact(root, "Sort by", "Sort by...", "Sort by…");
            }
            if (click(directSort)) {
                phase = FIND_MODIFIED;
                retryAttempt = 0;
                scheduleRetry();
                return;
            }

            // Pixel Android 16 path: Sort by lives inside the overflow menu.
            AccessibilityNodeInfo overflow = firstById(root,
                    "com.google.android.documentsui:id/action_menu_overflow",
                    "com.android.documentsui:id/action_menu_overflow",
                    "android:id/action_menu_overflow");
            if (overflow == null) {
                overflow = findExact(root, "More options", "More options button");
            }
            if (click(overflow)) {
                phase = FIND_SORT_ENTRY;
                retryAttempt = 0;
                scheduleRetry();
                return;
            }

            scheduleRetry();
            return;
        }

        if (phase == FIND_SORT_ENTRY) {
            AccessibilityNodeInfo sortEntry = findExact(root,
                    "Sort by...", "Sort by…", "Sort by");
            if (sortEntry == null) {
                sortEntry = firstById(root,
                        "com.google.android.documentsui:id/menu_sort",
                        "com.android.documentsui:id/menu_sort",
                        "com.google.android.documentsui:id/option_menu_sort",
                        "com.android.documentsui:id/option_menu_sort");
            }
            if (click(sortEntry)) {
                phase = FIND_MODIFIED;
                retryAttempt = 0;
                scheduleRetry();
                return;
            }

            scheduleRetry();
            return;
        }

        if (phase == FIND_MODIFIED) {
            AccessibilityNodeInfo modified = findModifiedNewest(root);
            if (click(modified)) {
                markApplied(true);
                return;
            }
            scheduleRetry();
        }
    }

    private AccessibilityNodeInfo findModifiedNewest(AccessibilityNodeInfo root) {
        // Modern Android 16 exposes the fully explicit direction. Prefer that so we never
        // accidentally choose "oldest first".
        AccessibilityNodeInfo node = findExact(root, "Modified (newest first)");
        if (node != null) return node;

        // Legacy DocumentsUI has one Last Modified menu action whose order is newest first.
        node = firstById(root,
                "com.google.android.documentsui:id/menu_sort_date",
                "com.android.documentsui:id/menu_sort_date");
        if (node != null) return node;

        return findExact(root, "By date modified", "Date modified");
    }

    private boolean isAlreadyModifiedNewest(AccessibilityNodeInfo root) {
        return treeContains(root,
                "Sorted by Modified (newest first)",
                "Sorted by By date modified");
    }

    private void markApplied(boolean changed) {
        phase = APPLIED;
        retryAttempt = 0;
        retryScheduled = false;
        generation++;
        handler.removeCallbacksAndMessages(null);

        if (changed) {
            getSharedPreferences("runtime", MODE_PRIVATE)
                    .edit()
                    .putLong("last_success_ms", System.currentTimeMillis())
                    .apply();
        }

        // Passive only: watch for DocumentsUI to disappear so the next picker can get one
        // fresh startup action. No clicks happen from this point onward.
        scheduleExitCheck(generation, 300);
    }

    private void abortForSession() {
        phase = ABORTED;
        retryAttempt = 0;
        retryScheduled = false;
        generation++;
        handler.removeCallbacksAndMessages(null);
        scheduleExitCheck(generation, 300);
    }

    private void scheduleRetry() {
        if (phase == IDLE || phase == APPLIED || phase == ABORTED) return;
        if (retryScheduled) return;
        if (startupWindowExpired()) {
            abortForSession();
            return;
        }

        final int expectedGeneration = generation;
        final long delayMs = retryDelay(retryAttempt);
        retryScheduled = true;

        handler.postDelayed(() -> {
            if (generation != expectedGeneration) return;
            retryScheduled = false;
            retryAttempt++;
            if (phase != IDLE && phase != APPLIED && phase != ABORTED) {
                tryAdvance();
            }
        }, delayMs);
    }

    private static long retryDelay(int attempt) {
        switch (attempt) {
            case 0: return 6;
            case 1: return 10;
            case 2: return 14;
            case 3: return 20;
            case 4: return 28;
            case 5: return 40;
            case 6: return 56;
            case 7: return 80;
            case 8: return 112;
            case 9: return 160;
            default: return 220;
        }
    }

    private void scheduleExitCheck(int expectedGeneration, long delayMs) {
        handler.postDelayed(() -> {
            if (generation != expectedGeneration
                    || (phase != APPLIED && phase != ABORTED)) {
                return;
            }

            if (isDocumentsUiWindowPresent()) {
                exitMisses = 0;
                scheduleExitCheck(expectedGeneration, 450);
            } else {
                exitMisses++;
                // Require two misses so a transient popup/window transition cannot re-arm us.
                if (exitMisses >= 2) {
                    resetSession();
                } else {
                    scheduleExitCheck(expectedGeneration, 120);
                }
            }
        }, delayMs);
    }

    private boolean isDocumentsUiWindowPresent() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) return false;
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                CharSequence pkg = root.getPackageName();
                if (pkg != null && isDocumentsUi(pkg.toString())) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    private static boolean isDocumentsUi(String pkg) {
        return "com.google.android.documentsui".equals(pkg)
                || "com.android.documentsui".equals(pkg)
                || pkg.endsWith(".documentsui");
    }

    private static AccessibilityNodeInfo firstById(
            AccessibilityNodeInfo root, String... ids) {
        if (root == null) return null;
        for (String id : ids) {
            try {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
                if (nodes == null) continue;
                for (AccessibilityNodeInfo node : nodes) {
                    if (node != null && node.isVisibleToUser() && node.isEnabled()) {
                        AccessibilityNodeInfo clickable = clickableAncestor(node);
                        if (clickable != null && clickable.isVisibleToUser()) return clickable;
                    }
                }
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    private static AccessibilityNodeInfo findExact(
            AccessibilityNodeInfo root, String... labels) {
        if (root == null) return null;

        CharSequence text = root.getText();
        CharSequence desc = root.getContentDescription();
        for (String label : labels) {
            if ((text != null && label.contentEquals(text))
                    || (desc != null && label.contentEquals(desc))) {
                AccessibilityNodeInfo clickable = clickableAncestor(root);
                if (clickable != null && clickable.isVisibleToUser()) return clickable;
            }
        }

        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findExact(root.getChild(i), labels);
            if (hit != null) return hit;
        }
        return null;
    }

    private static boolean treeContains(AccessibilityNodeInfo root, String... needles) {
        if (root == null) return false;
        CharSequence text = root.getText();
        CharSequence desc = root.getContentDescription();
        for (String needle : needles) {
            if ((text != null && text.toString().contains(needle))
                    || (desc != null && desc.toString().contains(needle))) {
                return true;
            }
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            if (treeContains(root.getChild(i), needles)) return true;
        }
        return false;
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; current != null && i < 6; i++) {
            if (current.isClickable() && current.isEnabled()) return current;
            current = current.getParent();
        }
        return null;
    }

    private static boolean click(AccessibilityNodeInfo node) {
        return node != null
                && node.isVisibleToUser()
                && node.isEnabled()
                && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private void resetSession() {
        phase = IDLE;
        retryAttempt = 0;
        retryScheduled = false;
        sessionStartedMs = 0;
        exitMisses = 0;
        generation++;
        handler.removeCallbacksAndMessages(null);
    }
}
