package com.mekromn.betterpicker;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.io.File;

public final class BetterPickerAccessibilityService extends AccessibilityService
        implements LocalFileBrowserView.Listener {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private LocalFileBrowserView overlay;
    private PickerMode activeMode = PickerMode.OPEN;
    private boolean handoffInProgress;
    private long lastPickerEventMs;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkgCs = event.getPackageName();
        CharSequence clsCs = event.getClassName();
        if (pkgCs == null || clsCs == null) return;

        String pkg = pkgCs.toString();
        String cls = clsCs.toString();
        if (!isDocumentsUi(pkg) || !isPickerClass(cls)) return;
        if (handoffInProgress || overlay != null) return;

        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastPickerEventMs < 250) return;
        lastPickerEventMs = now;
        handler.postDelayed(this::captureModeAndShow, 140);
    }

    @Override
    public void onInterrupt() {
        removeOverlay();
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }

    private boolean isDocumentsUi(String pkg) {
        return "com.android.documentsui".equals(pkg)
                || "com.google.android.documentsui".equals(pkg)
                || pkg.endsWith(".documentsui");
    }

    private boolean isPickerClass(String cls) {
        return cls.contains("picker.PickActivity")
                || cls.endsWith(".DocumentsActivity");
    }

    private void captureModeAndShow() {
        if (overlay != null || handoffInProgress) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        activeMode = detectMode(root);
        if (!Environment.isExternalStorageManager()) {
            Toast.makeText(this,
                    "Better Picker needs All files access. Open the Better Picker app to grant it.",
                    Toast.LENGTH_LONG).show();
        }
        showOverlay(activeMode);
    }

    private PickerMode detectMode(AccessibilityNodeInfo root) {
        if (DocumentUiDriver.treeContainsText(root, "Use this folder")) {
            return PickerMode.TREE;
        }
        if (DocumentUiDriver.hasEditableNode(root)
                && (DocumentUiDriver.treeContainsText(root, "Save")
                || DocumentUiDriver.treeContainsText(root, "Create"))) {
            return PickerMode.CREATE;
        }
        return PickerMode.OPEN;
    }

    private void showOverlay(PickerMode mode) {
        if (windowManager == null || overlay != null) return;
        overlay = new LocalFileBrowserView(this, mode, this);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        windowManager.addView(overlay, lp);
    }

    private void removeOverlay() {
        if (overlay != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(overlay);
            } catch (Exception ignored) { }
            overlay = null;
        }
    }

    private void beginHandoff(DocumentUiDriver.Action action) {
        removeOverlay();
        handoffInProgress = true;
        handler.postDelayed(() -> {
            DocumentUiDriver driver = new DocumentUiDriver(this, () -> {
                handoffInProgress = false;
            });
            action.run(driver);
        }, 220);
    }

    @Override
    public void onFileSelected(File file) {
        beginHandoff(driver -> driver.selectFile(file));
    }

    @Override
    public void onFolderSelected(File folder) {
        beginHandoff(driver -> driver.selectFolder(folder));
    }

    @Override
    public void onCreateFile(File folder, String displayName) {
        beginHandoff(driver -> driver.createFile(folder, displayName));
    }

    @Override
    public void onCancel() {
        removeOverlay();
        performGlobalAction(GLOBAL_ACTION_BACK);
    }
}
