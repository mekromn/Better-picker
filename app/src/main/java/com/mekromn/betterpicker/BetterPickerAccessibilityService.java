package com.mekromn.betterpicker;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public final class BetterPickerAccessibilityService extends AccessibilityService {
    private static final int IDLE = 0;
    private static final int FIND_SORT = 1;
    private static final int FIND_SORT_ENTRY = 2;
    private static final int FIND_MODIFIED = 3;
    private static final int APPLIED = 4;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int phase = IDLE;
    private int generation;
    private int retryStep;
    private long sessionStartedMs;

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

        // Do not depend on a specific activity class. Android 16 has changed picker
        // internals across builds, while the DocumentsUI package is the stable boundary.
        if (phase == IDLE || sessionExpired()) {
            beginSession();
        }

        if (phase != APPLIED) {
            tryAdvance();
        }
    }

    @Override
    public void onInterrupt() {
        resetSession();
    }

    private void beginSession() {
        generation++;
        phase = FIND_SORT;
        retryStep = 0;
        sessionStartedMs = android.os.SystemClock.uptimeMillis();
        handler.removeCallbacksAndMessages(null);
    }

    private boolean sessionExpired() {
        return phase != IDLE
                && android.os.SystemClock.uptimeMillis() - sessionStartedMs > 15000;
    }

    private void tryAdvance() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            scheduleRetry();
            return;
        }

        // Modern Android 16 path can land directly in the sorting bottom sheet after
        // a content event, so always prefer completing the operation if that option exists.
        AccessibilityNodeInfo modified = findModified(root);
        if (modified != null && click(modified)) {
            markApplied();
            return;
        }

        if (phase == FIND_SORT) {
            // Older/AOSP layouts may expose Sort by directly in the toolbar.
            AccessibilityNodeInfo sort = firstById(root,
                    "com.google.android.documentsui:id/menu_sort",
                    "com.android.documentsui:id/menu_sort",
                    "com.google.android.documentsui:id/option_menu_sort",
                    "com.android.documentsui:id/option_menu_sort");
            if (sort == null) {
                sort = findExact(root,
                        "Sort by", "Sort by...", "Sort by…");
            }
            if (click(sort)) {
                phase = FIND_MODIFIED;
                retryStep = 0;
                scheduleRetry();
                return;
            }

            // Pixel Android 16 puts Sort by... inside the three-dot overflow. The
            // overflow button is exposed to accessibility as "More options".
            AccessibilityNodeInfo overflow = findExact(root,
                    "More options", "More options button");
            if (overflow == null) {
                overflow = firstById(root,
                        "com.google.android.documentsui:id/action_menu_overflow",
                        "com.android.documentsui:id/action_menu_overflow",
                        "android:id/action_menu_overflow");
            }
            if (click(overflow)) {
                phase = FIND_SORT_ENTRY;
                retryStep = 0;
                scheduleRetry();
                return;
            }

            scheduleRetry();
            return;
        }

        if (phase == FIND_SORT_ENTRY) {
            // The overflow popup is a separate accessibility window. Its text is
            // "Sort by..." in current DocumentsUI.
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
                retryStep = 0;
                scheduleRetry();
                return;
            }
            scheduleRetry();
            return;
        }

        if (phase == FIND_MODIFIED) {
            // findModified above already tried the modern and legacy labels/IDs.
            scheduleRetry();
        }
    }

    private AccessibilityNodeInfo findModified(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo node = firstById(root,
                "com.google.android.documentsui:id/menu_sort_date",
                "com.android.documentsui:id/menu_sort_date");
        if (node != null) return node;

        // Current DocumentsUI sorting bottom sheet terminology first, then legacy.
        return findExact(root,
                "Modified (newest first)",
                "By date modified",
                "Date modified");
    }

    private void markApplied() {
        phase = APPLIED;
        retryStep = 0;
        generation++;
        handler.removeCallbacksAndMessages(null);
        getSharedPreferences("runtime", MODE_PRIVATE)
                .edit()
                .putLong("last_success_ms", System.currentTimeMillis())
                .apply();
    }

    private void scheduleRetry() {
        if (phase == APPLIED || phase == IDLE || retryStep >= 10) return;
        final int expectedGeneration = generation;
        final int step = retryStep++;
        final long delayMs;
        switch (step) {
            case 0: delayMs = 8; break;
            case 1: delayMs = 12; break;
            case 2: delayMs = 16; break;
            case 3: delayMs = 24; break;
            case 4: delayMs = 36; break;
            case 5: delayMs = 54; break;
            case 6: delayMs = 80; break;
            case 7: delayMs = 120; break;
            case 8: delayMs = 180; break;
            default: delayMs = 260; break;
        }
        handler.postDelayed(() -> {
            if (generation == expectedGeneration && phase != APPLIED && phase != IDLE) {
                tryAdvance();
            }
        }, delayMs);
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
                if (nodes != null) {
                    for (AccessibilityNodeInfo node : nodes) {
                        if (node != null && node.isVisibleToUser() && node.isEnabled()) {
                            AccessibilityNodeInfo clickable = clickableAncestor(node);
                            if (clickable != null) return clickable;
                        }
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
                if (clickable != null) return clickable;
            }
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            AccessibilityNodeInfo hit = findExact(child, labels);
            if (hit != null) return hit;
        }
        return null;
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; current != null && i < 6; i++) {
            if (current.isClickable() && current.isEnabled()) return current;
            current = current.getParent();
        }
        return node != null && node.isEnabled() ? node : null;
    }

    private static boolean click(AccessibilityNodeInfo node) {
        return node != null
                && node.isEnabled()
                && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private void resetSession() {
        phase = IDLE;
        retryStep = 0;
        sessionStartedMs = 0;
        generation++;
        handler.removeCallbacksAndMessages(null);
    }
}
