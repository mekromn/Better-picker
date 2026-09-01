package com.mekromn.betterpicker;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.Locale;

public final class BetterPickerAccessibilityService extends AccessibilityService {
    private static final int IDLE = 0;
    private static final int WAIT_LOCATION = 1;
    private static final int FIND_ENTRY = 2;
    private static final int FIND_SORT_ENTRY = 3;
    private static final int FIND_MODIFIED = 4;
    private static final int APPLIED = 5;
    private static final int ABORTED = 6;

    private static final int LOCATION_UNKNOWN = 0;
    private static final int LOCATION_RECENTS = 1;
    private static final int LOCATION_NORMAL = 2;

    // A sort attempt only gets a short window immediately after a real folder/root appears.
    // If it cannot finish here, it stands down rather than fighting the user later.
    private static final long ACTION_WINDOW_MS = 1400;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private int phase = IDLE;
    private int generation;
    private int retryAttempt;
    private boolean retryScheduled;
    private int locationProbeAttempt;
    private boolean locationProbeScheduled;
    private long actionWindowStartedMs;
    private int exitMisses;

    private String documentsPackage;
    private String localizedRecents;
    private String documentsAppLabel;
    private String filesLabel;

    private long ownClickValidUntilMs;
    private String ownClickViewId;
    private String ownClickLabel;

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
        String pkg = pkgCs.toString();

        if (phase == IDLE) {
            beginSession(pkg);
        }

        if (phase == APPLIED || phase == ABORTED) return;

        // Once a real folder is visible, the user's own click always wins. We only ignore the
        // click event that corresponds to an ACTION_CLICK we just issued ourselves.
        if (isActionPhase(phase)
                && event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED
                && !isOurOwnClickEvent(event)) {
            abortForSession();
            return;
        }

        if (phase == WAIT_LOCATION) {
            evaluateLocation();
        } else {
            tryAdvanceSort();
        }
    }

    @Override
    public void onInterrupt() {
        resetSession();
    }

    private void beginSession(String pkg) {
        generation++;
        phase = WAIT_LOCATION;
        retryAttempt = 0;
        retryScheduled = false;
        locationProbeAttempt = 0;
        locationProbeScheduled = false;
        actionWindowStartedMs = 0;
        exitMisses = 0;
        ownClickValidUntilMs = 0;
        ownClickViewId = null;
        ownClickLabel = null;
        documentsPackage = pkg;
        loadDocumentsUiLabels(pkg);
        handler.removeCallbacksAndMessages(null);
        scheduleSessionPresenceCheck(generation, 500);
        evaluateLocation();
    }

    private void evaluateLocation() {
        if (phase != WAIT_LOCATION) return;

        int location = detectCurrentLocation();
        if (location == LOCATION_RECENTS) {
            // Recents has its own useful ordering. Stay completely passive for as long as the
            // user remains here. Navigation events will call us again when the title changes.
            locationProbeScheduled = false;
            locationProbeAttempt = 0;
            return;
        }

        if (location == LOCATION_NORMAL) {
            armSortForCurrentLocation();
            return;
        }

        // Initial activity/title setup can briefly be unknown. Probe quickly without clicking
        // anything, then fall back to waiting for genuine DocumentsUI navigation events.
        scheduleLocationProbe();
    }

    private void armSortForCurrentLocation() {
        if (phase != WAIT_LOCATION) return;
        phase = FIND_ENTRY;
        actionWindowStartedMs = SystemClock.uptimeMillis();
        retryAttempt = 0;
        retryScheduled = false;
        locationProbeScheduled = false;
        tryAdvanceSort();
    }

    private boolean actionWindowExpired() {
        return actionWindowStartedMs == 0
                || SystemClock.uptimeMillis() - actionWindowStartedMs > ACTION_WINDOW_MS;
    }

    private void tryAdvanceSort() {
        if (!isActionPhase(phase)) return;

        if (actionWindowExpired()) {
            abortForSession();
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            scheduleRetry();
            return;
        }

        // Never act if navigation returned to Recents while we were waiting for a transition.
        if (detectCurrentLocation() == LOCATION_RECENTS) {
            phase = WAIT_LOCATION;
            actionWindowStartedMs = 0;
            retryAttempt = 0;
            retryScheduled = false;
            return;
        }

        // If DocumentsUI already remembered the desired order for this normal location, latch
        // off without opening any menus.
        if (isAlreadyModifiedNewest(root)) {
            markApplied(false);
            return;
        }

        if (phase == FIND_ENTRY) {
            AccessibilityNodeInfo directSort = firstById(root,
                    "com.google.android.documentsui:id/menu_sort",
                    "com.android.documentsui:id/menu_sort",
                    "com.google.android.documentsui:id/option_menu_sort",
                    "com.android.documentsui:id/option_menu_sort");
            if (directSort == null) {
                directSort = findExact(root, "Sort by", "Sort by...", "Sort by…");
            }
            if (clickNode(directSort)) {
                phase = FIND_MODIFIED;
                retryAttempt = 0;
                scheduleRetry();
                return;
            }

            AccessibilityNodeInfo overflow = firstById(root,
                    "com.google.android.documentsui:id/action_menu_overflow",
                    "com.android.documentsui:id/action_menu_overflow",
                    "android:id/action_menu_overflow");
            if (overflow == null) {
                overflow = findExact(root, "More options", "More options button");
            }
            if (clickNode(overflow)) {
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
            if (clickNode(sortEntry)) {
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
            if (clickNode(modified)) {
                markApplied(true);
                return;
            }
            scheduleRetry();
        }
    }

    private int detectCurrentLocation() {
        PrimaryWindow primary = getPrimaryDocumentsUiWindow();
        if (primary == null) return LOCATION_UNKNOWN;

        String title = clean(primary.title);
        if (title.isEmpty() && primary.root != null) {
            title = clean(readToolbarTitle(primary.root));
        }
        if (title.isEmpty()) return LOCATION_UNKNOWN;

        if (isRecentsTitle(title)) return LOCATION_RECENTS;
        if (isGenericDocumentsTitle(title)) return LOCATION_UNKNOWN;
        return LOCATION_NORMAL;
    }

    private boolean isRecentsTitle(String title) {
        String value = normalize(title);
        if (value.equals("recent") || value.equals("recents")) return true;
        return localizedRecents != null && value.equals(normalize(localizedRecents));
    }

    private boolean isGenericDocumentsTitle(String title) {
        String value = normalize(title);
        if (value.isEmpty()) return true;
        if (documentsAppLabel != null && value.equals(normalize(documentsAppLabel))) return true;
        if (filesLabel != null && value.equals(normalize(filesLabel))) return true;
        return value.equals("files") || value.equals("documents");
    }

    private String readToolbarTitle(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo node = firstVisibleById(root,
                "android:id/action_bar_title",
                "com.google.android.documentsui:id/action_bar_title",
                "com.android.documentsui:id/action_bar_title",
                "com.google.android.documentsui:id/toolbar_title",
                "com.android.documentsui:id/toolbar_title");
        if (node == null) return "";
        CharSequence text = node.getText();
        return text == null ? "" : text.toString();
    }

    private PrimaryWindow getPrimaryDocumentsUiWindow() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) return null;

            PrimaryWindow best = null;
            long bestArea = -1;
            Rect bounds = new Rect();
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                CharSequence pkg = root.getPackageName();
                if (pkg == null || !isDocumentsUi(pkg.toString())) continue;

                window.getBoundsInScreen(bounds);
                long area = Math.max(0, bounds.width()) * (long) Math.max(0, bounds.height());
                if (area > bestArea) {
                    CharSequence title = window.getTitle();
                    best = new PrimaryWindow(root, title == null ? "" : title.toString());
                    bestArea = area;
                }
            }
            return best;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private AccessibilityNodeInfo findModifiedNewest(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo node = findExact(root, "Modified (newest first)");
        if (node != null) return node;

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
        locationProbeScheduled = false;
        actionWindowStartedMs = 0;
        clearOwnClickMarker();

        if (changed) {
            getSharedPreferences("runtime", MODE_PRIVATE)
                    .edit()
                    .putLong("last_success_ms", System.currentTimeMillis())
                    .apply();
        }
    }

    private void abortForSession() {
        phase = ABORTED;
        retryAttempt = 0;
        retryScheduled = false;
        locationProbeScheduled = false;
        actionWindowStartedMs = 0;
        clearOwnClickMarker();
    }

    private void scheduleLocationProbe() {
        if (phase != WAIT_LOCATION || locationProbeScheduled || locationProbeAttempt >= 9) return;
        final int expectedGeneration = generation;
        final long delayMs = locationProbeDelay(locationProbeAttempt++);
        locationProbeScheduled = true;
        handler.postDelayed(() -> {
            if (generation != expectedGeneration || phase != WAIT_LOCATION) return;
            locationProbeScheduled = false;
            evaluateLocation();
        }, delayMs);
    }

    private static long locationProbeDelay(int attempt) {
        switch (attempt) {
            case 0: return 8;
            case 1: return 14;
            case 2: return 24;
            case 3: return 40;
            case 4: return 64;
            case 5: return 96;
            case 6: return 144;
            case 7: return 220;
            default: return 320;
        }
    }

    private void scheduleRetry() {
        if (!isActionPhase(phase) || retryScheduled) return;
        if (actionWindowExpired()) {
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
            if (isActionPhase(phase)) tryAdvanceSort();
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

    private void scheduleSessionPresenceCheck(int expectedGeneration, long delayMs) {
        handler.postDelayed(() -> {
            if (generation != expectedGeneration || phase == IDLE) return;

            if (isDocumentsUiWindowPresent()) {
                exitMisses = 0;
                scheduleSessionPresenceCheck(expectedGeneration, 450);
            } else {
                exitMisses++;
                if (exitMisses >= 2) {
                    resetSession();
                } else {
                    scheduleSessionPresenceCheck(expectedGeneration, 120);
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

    private boolean clickNode(AccessibilityNodeInfo node) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()) return false;

        ownClickViewId = node.getViewIdResourceName();
        ownClickLabel = nodeLabel(node);
        ownClickValidUntilMs = SystemClock.uptimeMillis() + 500;
        boolean clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) clearOwnClickMarker();
        return clicked;
    }

    private boolean isOurOwnClickEvent(AccessibilityEvent event) {
        if (SystemClock.uptimeMillis() > ownClickValidUntilMs) return false;
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return false;

        String sourceId = source.getViewIdResourceName();
        if (ownClickViewId != null && ownClickViewId.equals(sourceId)) {
            clearOwnClickMarker();
            return true;
        }

        String sourceLabel = nodeLabel(source);
        if (ownClickLabel != null && !ownClickLabel.isEmpty() && ownClickLabel.equals(sourceLabel)) {
            clearOwnClickMarker();
            return true;
        }
        return false;
    }

    private void clearOwnClickMarker() {
        ownClickValidUntilMs = 0;
        ownClickViewId = null;
        ownClickLabel = null;
    }

    private void loadDocumentsUiLabels(String pkg) {
        documentsPackage = pkg;
        localizedRecents = resourceString(pkg, "root_recent");
        filesLabel = resourceString(pkg, "files_label");
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            CharSequence label = getPackageManager().getApplicationLabel(info);
            documentsAppLabel = label == null ? null : label.toString();
        } catch (Exception ignored) {
            documentsAppLabel = null;
        }
    }

    private String resourceString(String pkg, String name) {
        try {
            Context packageContext = createPackageContext(pkg, 0);
            int id = packageContext.getResources().getIdentifier(name, "string", pkg);
            return id == 0 ? null : packageContext.getString(id);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isActionPhase(int value) {
        return value == FIND_ENTRY || value == FIND_SORT_ENTRY || value == FIND_MODIFIED;
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

    private static AccessibilityNodeInfo firstVisibleById(
            AccessibilityNodeInfo root, String... ids) {
        if (root == null) return null;
        for (String id : ids) {
            try {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
                if (nodes == null) continue;
                for (AccessibilityNodeInfo node : nodes) {
                    if (node != null && node.isVisibleToUser()) return node;
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

    private static String nodeLabel(AccessibilityNodeInfo node) {
        if (node == null) return "";
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) return text.toString();
        CharSequence desc = node.getContentDescription();
        return desc == null ? "" : desc.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalize(String value) {
        return clean(value).toLowerCase(Locale.ROOT);
    }

    private void resetSession() {
        phase = IDLE;
        retryAttempt = 0;
        retryScheduled = false;
        locationProbeAttempt = 0;
        locationProbeScheduled = false;
        actionWindowStartedMs = 0;
        exitMisses = 0;
        documentsPackage = null;
        localizedRecents = null;
        documentsAppLabel = null;
        filesLabel = null;
        clearOwnClickMarker();
        generation++;
        handler.removeCallbacksAndMessages(null);
    }

    private static final class PrimaryWindow {
        final AccessibilityNodeInfo root;
        final String title;

        PrimaryWindow(AccessibilityNodeInfo root, String title) {
            this.root = root;
            this.title = title;
        }
    }
}
