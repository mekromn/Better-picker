package com.mekromn.betterpicker;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public final class BetterPickerAccessibilityService extends AccessibilityService {
    private static final int IDLE = 0;
    private static final int WAITING_SORT = 1;
    private static final int WAITING_MODIFIED = 2;
    private static final int APPLIED = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int pickerWindowId = -1;
    private int phase = IDLE;
    private int generation;
    private int retryStep;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        resetSession();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                && (event.getWindowChanges() & AccessibilityEvent.WINDOWS_CHANGE_REMOVED) != 0
                && event.getWindowId() == pickerWindowId) {
            resetSession();
            return;
        }

        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null || !isDocumentsUi(pkgCs.toString())) return;

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence clsCs = event.getClassName();
            String cls = clsCs == null ? "" : clsCs.toString();
            if (isPickerClass(cls) && event.getWindowId() != pickerWindowId) {
                pickerWindowId = event.getWindowId();
                phase = WAITING_SORT;
                retryStep = 0;
                generation++;
                tryAdvance();
                return;
            }
        }

        if (phase == WAITING_SORT || phase == WAITING_MODIFIED) {
            tryAdvance();
        }
    }

    @Override
    public void onInterrupt() {
        resetSession();
    }

    private void tryAdvance() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            scheduleRetry();
            return;
        }

        if (phase == WAITING_SORT) {
            AccessibilityNodeInfo sort = firstById(root,
                    "com.google.android.documentsui:id/menu_sort",
                    "com.android.documentsui:id/menu_sort");
            if (sort == null) {
                sort = findByText(root, "Sort by", "Sort");
            }
            if (click(sort)) {
                phase = WAITING_MODIFIED;
                retryStep = 0;
                scheduleRetry();
            } else {
                scheduleRetry();
            }
            return;
        }

        if (phase == WAITING_MODIFIED) {
            AccessibilityNodeInfo modified = firstById(root,
                    "com.google.android.documentsui:id/menu_sort_date",
                    "com.android.documentsui:id/menu_sort_date");
            if (modified == null) {
                modified = findByText(root,
                        "By date modified", "Date modified", "Modified");
            }
            if (click(modified)) {
                phase = APPLIED;
                retryStep = 0;
                generation++;
            } else {
                scheduleRetry();
            }
        }
    }

    private void scheduleRetry() {
        if (phase == APPLIED || retryStep >= 7) return;
        final int expectedGeneration = generation;
        final int step = retryStep++;
        final long delayMs;
        switch (step) {
            case 0: delayMs = 16; break;
            case 1: delayMs = 24; break;
            case 2: delayMs = 40; break;
            case 3: delayMs = 64; break;
            case 4: delayMs = 96; break;
            case 5: delayMs = 144; break;
            default: delayMs = 220; break;
        }
        handler.postDelayed(() -> {
            if (generation == expectedGeneration
                    && (phase == WAITING_SORT || phase == WAITING_MODIFIED)) {
                tryAdvance();
            }
        }, delayMs);
    }

    private static boolean isDocumentsUi(String pkg) {
        return "com.google.android.documentsui".equals(pkg)
                || "com.android.documentsui".equals(pkg);
    }

    private static boolean isPickerClass(String cls) {
        return cls.contains("PickActivity")
                || cls.endsWith("DocumentsActivity")
                || cls.contains(".picker.");
    }

    private static AccessibilityNodeInfo firstById(
            AccessibilityNodeInfo root, String... ids) {
        for (String id : ids) {
            try {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
                if (nodes != null) {
                    for (AccessibilityNodeInfo node : nodes) {
                        if (node != null && node.isVisibleToUser() && node.isEnabled()) {
                            return clickableAncestor(node);
                        }
                    }
                }
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    private static AccessibilityNodeInfo findByText(
            AccessibilityNodeInfo root, String... labels) {
        if (root == null) return null;
        CharSequence text = root.getText();
        CharSequence desc = root.getContentDescription();
        for (String label : labels) {
            if ((text != null && label.contentEquals(text))
                    || (desc != null && label.contentEquals(desc))) {
                return clickableAncestor(root);
            }
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findByText(root.getChild(i), labels);
            if (hit != null) return hit;
        }
        return null;
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; current != null && i < 5; i++) {
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
        pickerWindowId = -1;
        phase = IDLE;
        retryStep = 0;
        generation++;
        handler.removeCallbacksAndMessages(null);
    }
}
