package com.mekromn.betterpicker;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class DocumentUiDriver {
    interface Done { void run(); }
    interface Action { void run(DocumentUiDriver driver); }

    private final AccessibilityService service;
    private final Done done;
    private final Handler handler = new Handler(Looper.getMainLooper());

    DocumentUiDriver(AccessibilityService service, Done done) {
        this.service = service;
        this.done = done;
    }

    void selectFile(File file) {
        File parent = file.getParentFile();
        if (parent == null) {
            fail("Unable to resolve the selected file's folder");
            return;
        }
        navigateTo(parent, () -> clickExactFile(file.getName(), 0));
    }

    void selectFolder(File folder) {
        navigateTo(folder, () -> clickActionButton(new String[]{"Use this folder", "Select folder"}, 0));
    }

    void createFile(File folder, String displayName) {
        navigateTo(folder, () -> setFilenameAndSave(displayName, 0));
    }

    private void navigateTo(File targetDir, Runnable after) {
        File root = Environment.getExternalStorageDirectory();
        String relative;
        try {
            String rootPath = root.getCanonicalPath();
            String targetPath = targetDir.getCanonicalPath();
            if (!(targetPath.equals(rootPath) || targetPath.startsWith(rootPath + File.separator))) {
                fail("Target is outside internal shared storage");
                return;
            }
            relative = targetPath.substring(rootPath.length());
        } catch (Exception e) {
            fail("Unable to resolve target path");
            return;
        }

        openStorageRoot(0, () -> {
            String clean = relative.startsWith(File.separator) ? relative.substring(1) : relative;
            if (clean.isEmpty()) {
                handler.postDelayed(after, 180);
                return;
            }
            String[] parts = clean.split(java.util.regex.Pattern.quote(File.separator));
            openPathParts(parts, 0, after);
        });
    }

    private void openStorageRoot(int attempt, Runnable after) {
        if (attempt > 10) {
            fail("Could not open internal storage in the system picker");
            return;
        }
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            handler.postDelayed(() -> openStorageRoot(attempt + 1, after), 160);
            return;
        }

        AccessibilityNodeInfo model = findClickableText(root, Build.MODEL);
        AccessibilityNodeInfo internal = firstNonNull(
                findClickableText(root, "Internal storage"),
                findClickableText(root, "Internal shared storage"),
                model);
        if (internal != null) {
            internal.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            handler.postDelayed(after, 280);
            return;
        }

        AccessibilityNodeInfo drawer = findByDescription(root,
                new String[]{"Show roots", "Show storage roots", "Navigation menu", "Open navigation drawer"});
        if (drawer != null) {
            drawer.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            handler.postDelayed(() -> openStorageRoot(attempt + 1, after), 220);
            return;
        }

        // If the toolbar exposes only a generic clickable button, try the first one whose
        // class is ImageButton near the top of the hierarchy.
        AccessibilityNodeInfo nav = findFirstClickableClass(root, "android.widget.ImageButton");
        if (nav != null) {
            nav.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            handler.postDelayed(() -> openStorageRoot(attempt + 1, after), 220);
            return;
        }

        handler.postDelayed(() -> openStorageRoot(attempt + 1, after), 180);
    }

    private void openPathParts(String[] parts, int index, Runnable after) {
        if (index >= parts.length) {
            handler.postDelayed(after, 180);
            return;
        }
        String part = parts[index];
        findAndClickWithScroll(part, 0, success -> {
            if (!success) {
                fail("System picker could not locate folder: " + part);
                return;
            }
            handler.postDelayed(() -> openPathParts(parts, index + 1, after), 220);
        });
    }

    private interface BoolDone { void run(boolean value); }

    private void findAndClickWithScroll(String text, int attempt, BoolDone callback) {
        if (attempt > 14) {
            callback.run(false);
            return;
        }
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            handler.postDelayed(() -> findAndClickWithScroll(text, attempt + 1, callback), 120);
            return;
        }
        AccessibilityNodeInfo node = findClickableText(root, text);
        if (node != null && node.isEnabled()) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            callback.run(true);
            return;
        }
        AccessibilityNodeInfo scroller = findScrollable(root);
        if (scroller != null && scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            handler.postDelayed(() -> findAndClickWithScroll(text, attempt + 1, callback), 120);
        } else {
            callback.run(false);
        }
    }

    private void clickExactFile(String filename, int attempt) {
        if (attempt > 12) {
            // Fallback to exact-name search if folder navigation rendered a virtualized item
            // that we could not reach by scrolling.
            searchAndClick(filename, 0);
            return;
        }
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        AccessibilityNodeInfo node = root == null ? null : findClickableText(root, filename);
        if (node != null && node.isEnabled()) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            finishLater();
            return;
        }
        AccessibilityNodeInfo scroller = root == null ? null : findScrollable(root);
        if (scroller != null && scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            handler.postDelayed(() -> clickExactFile(filename, attempt + 1), 120);
        } else {
            searchAndClick(filename, 0);
        }
    }

    private void searchAndClick(String filename, int attempt) {
        if (attempt > 10) {
            fail("System picker could not hand off selected file: " + filename);
            return;
        }
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            handler.postDelayed(() -> searchAndClick(filename, attempt + 1), 150);
            return;
        }

        AccessibilityNodeInfo exact = findClickableText(root, filename);
        if (exact != null && exact.isEnabled()) {
            exact.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            finishLater();
            return;
        }

        AccessibilityNodeInfo searchButton = findByDescription(root, new String[]{"Search"});
        if (searchButton != null) {
            searchButton.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            handler.postDelayed(() -> {
                AccessibilityNodeInfo r2 = service.getRootInActiveWindow();
                AccessibilityNodeInfo edit = r2 == null ? null : findFirstEditable(r2);
                if (edit != null) {
                    Bundle args = new Bundle();
                    args.putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, filename);
                    edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                    handler.postDelayed(() -> searchAndClick(filename, attempt + 1), 420);
                } else {
                    handler.postDelayed(() -> searchAndClick(filename, attempt + 1), 160);
                }
            }, 180);
        } else {
            handler.postDelayed(() -> searchAndClick(filename, attempt + 1), 180);
        }
    }

    private void clickActionButton(String[] labels, int attempt) {
        if (attempt > 10) {
            fail("Could not confirm the selected folder in DocumentsUI");
            return;
        }
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root != null) {
            for (String label : labels) {
                AccessibilityNodeInfo node = findClickableText(root, label);
                if (node != null && node.isEnabled()) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    finishLater();
                    return;
                }
            }
        }
        handler.postDelayed(() -> clickActionButton(labels, attempt + 1), 150);
    }

    private void setFilenameAndSave(String name, int attempt) {
        if (attempt > 10) {
            fail("Could not complete Save in DocumentsUI");
            return;
        }
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        AccessibilityNodeInfo edit = root == null ? null : findFirstEditable(root);
        if (edit == null) {
            handler.postDelayed(() -> setFilenameAndSave(name, attempt + 1), 160);
            return;
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name);
        edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        handler.postDelayed(() -> {
            AccessibilityNodeInfo r2 = service.getRootInActiveWindow();
            AccessibilityNodeInfo save = r2 == null ? null : firstNonNull(
                    findClickableText(r2, "Save"), findClickableText(r2, "Create"));
            if (save != null && save.isEnabled()) {
                save.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                finishLater();
            } else {
                handler.postDelayed(() -> setFilenameAndSave(name, attempt + 1), 180);
            }
        }, 180);
    }

    private void finishLater() {
        handler.postDelayed(done::run, 500);
    }

    private void fail(String message) {
        Toast.makeText(service, message + ". Showing the system picker as fallback.", Toast.LENGTH_LONG).show();
        done.run();
    }

    static boolean treeContainsText(AccessibilityNodeInfo root, String needle) {
        if (root == null) return false;
        String lower = needle.toLowerCase(Locale.ROOT);
        CharSequence text = root.getText();
        if (text != null && text.toString().toLowerCase(Locale.ROOT).contains(lower)) return true;
        CharSequence desc = root.getContentDescription();
        if (desc != null && desc.toString().toLowerCase(Locale.ROOT).contains(lower)) return true;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null && treeContainsText(child, needle)) return true;
        }
        return false;
    }

    static boolean hasEditableNode(AccessibilityNodeInfo root) {
        return findFirstEditable(root) != null;
    }

    private static AccessibilityNodeInfo findClickableText(AccessibilityNodeInfo root, String exact) {
        if (root == null || exact == null) return null;
        CharSequence text = root.getText();
        if (text != null && exact.contentEquals(text)) {
            AccessibilityNodeInfo clickable = clickableAncestor(root);
            if (clickable != null) return clickable;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findClickableText(root.getChild(i), exact);
            if (hit != null) return hit;
        }
        return null;
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int depth = 0; cur != null && depth < 4; depth++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return node.isEnabled() ? node : null;
    }

    private static AccessibilityNodeInfo findByDescription(AccessibilityNodeInfo root, String[] candidates) {
        if (root == null) return null;
        CharSequence desc = root.getContentDescription();
        if (desc != null) {
            String d = desc.toString().toLowerCase(Locale.ROOT);
            for (String candidate : candidates) {
                if (d.contains(candidate.toLowerCase(Locale.ROOT)) && root.isClickable()) return root;
            }
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findByDescription(root.getChild(i), candidates);
            if (hit != null) return hit;
        }
        return null;
    }

    private static AccessibilityNodeInfo findFirstClickableClass(AccessibilityNodeInfo root, String className) {
        if (root == null) return null;
        CharSequence cls = root.getClassName();
        if (root.isClickable() && cls != null && className.contentEquals(cls)) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findFirstClickableClass(root.getChild(i), className);
            if (hit != null) return hit;
        }
        return null;
    }

    private static AccessibilityNodeInfo findFirstEditable(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (root.isEditable()) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findFirstEditable(root.getChild(i));
            if (hit != null) return hit;
        }
        return null;
    }

    private static AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (root.isScrollable()) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findScrollable(root.getChild(i));
            if (hit != null) return hit;
        }
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) if (value != null) return value;
        return null;
    }
}
