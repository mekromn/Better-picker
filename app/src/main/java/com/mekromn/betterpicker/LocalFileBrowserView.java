package com.mekromn.betterpicker;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Environment;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class LocalFileBrowserView extends FrameLayout {
    interface Listener {
        void onFileSelected(File file);
        void onFolderSelected(File folder);
        void onCreateFile(File folder, String displayName);
        void onCancel();
    }

    private final PickerMode mode;
    private final Listener listener;
    private final File storageRoot;
    private File currentDir;
    private final TextView pathView;
    private final EditText search;
    private final ListView list;
    private final FileAdapter adapter;
    private final EditText createName;
    private String requestedMimeType = "*/*";

    LocalFileBrowserView(Context context, PickerMode mode, Listener listener) {
        super(context);
        this.mode = mode;
        this.listener = listener;
        this.storageRoot = Environment.getExternalStorageDirectory();
        this.currentDir = storageRoot;
        setBackgroundColor(Color.rgb(16, 17, 20));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(12));
        addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);

        Button close = new Button(context);
        close.setText("✕");
        close.setTextSize(18);
        close.setOnClickListener(v -> listener.onCancel());
        top.addView(close, new LinearLayout.LayoutParams(dp(52), dp(48)));

        TextView title = new TextView(context);
        title.setText(mode == PickerMode.CREATE ? "Save file" : mode == PickerMode.TREE ? "Choose folder" : "Choose file");
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.rgb(241, 243, 244));
        title.setPadding(dp(10), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(top);

        pathView = new TextView(context);
        pathView.setTextSize(14);
        pathView.setTextColor(Color.rgb(138, 180, 248));
        pathView.setSingleLine(true);
        pathView.setPadding(dp(8), dp(8), dp(8), dp(8));
        pathView.setOnClickListener(v -> goParent());
        root.addView(pathView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        search = new EditText(context);
        search.setHint("Search this folder");
        search.setSingleLine(true);
        search.setTextColor(Color.rgb(241, 243, 244));
        search.setHintTextColor(Color.rgb(128, 134, 139));
        search.setBackgroundColor(Color.rgb(34, 37, 43));
        search.setPadding(dp(14), 0, dp(14), 0);
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        list = new ListView(context);
        list.setDividerHeight(0);
        adapter = new FileAdapter(context);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            File f = adapter.getItem(position);
            if (f.isDirectory()) {
                openDir(f);
            } else if (mode == PickerMode.OPEN && matchesMime(f)) {
                listener.onFileSelected(f);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        createName = new EditText(context);
        createName.setSingleLine(true);
        createName.setHint("File name");
        createName.setTextColor(Color.rgb(241, 243, 244));
        createName.setHintTextColor(Color.rgb(128, 134, 139));

        if (mode == PickerMode.CREATE) {
            root.addView(createName, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
            Button save = bottomButton(context, "Save here");
            save.setOnClickListener(v -> {
                String name = createName.getText().toString().trim();
                if (!name.isEmpty()) listener.onCreateFile(currentDir, name);
            });
            root.addView(save);
        } else if (mode == PickerMode.TREE) {
            Button use = bottomButton(context, "Use this folder");
            use.setOnClickListener(v -> listener.onFolderSelected(currentDir));
            root.addView(use);
        }

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh(); }
            @Override public void afterTextChanged(Editable s) { }
        });

        refresh();
    }

    void setRequestedMimeType(String type) {
        requestedMimeType = type == null ? "*/*" : type;
        refresh();
    }

    private Button bottomButton(Context context, String text) {
        Button b = new Button(context);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void openDir(File dir) {
        if (dir != null && dir.isDirectory() && isInsideRoot(dir)) {
            currentDir = dir;
            search.setText("");
            refresh();
        }
    }

    private void goParent() {
        if (!currentDir.equals(storageRoot)) {
            File parent = currentDir.getParentFile();
            if (parent != null && isInsideRoot(parent)) openDir(parent);
        }
    }

    private boolean isInsideRoot(File f) {
        try {
            String root = storageRoot.getCanonicalPath();
            String path = f.getCanonicalPath();
            return path.equals(root) || path.startsWith(root + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean matchesMime(File file) {
        if (requestedMimeType == null || "*/*".equals(requestedMimeType)) return true;
        String mime = URLConnection.guessContentTypeFromName(file.getName());
        if (mime == null) return false;
        if (requestedMimeType.endsWith("/*")) {
            return mime.startsWith(requestedMimeType.substring(0, requestedMimeType.length() - 1));
        }
        return requestedMimeType.equalsIgnoreCase(mime);
    }

    private void refresh() {
        pathView.setText(currentDir.equals(storageRoot)
                ? "Internal storage"
                : "Internal storage" + currentDir.getAbsolutePath().substring(storageRoot.getAbsolutePath().length()));

        File[] raw = currentDir.listFiles();
        List<File> files = new ArrayList<>();
        if (raw != null) files.addAll(Arrays.asList(raw));
        Collections.sort(files, Comparator
                .comparing((File f) -> !f.isDirectory())
                .thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));

        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<File> visible = new ArrayList<>();
        for (File f : files) {
            if (!q.isEmpty() && !f.getName().toLowerCase(Locale.ROOT).contains(q)) continue;
            if (f.isDirectory() || mode != PickerMode.OPEN || matchesMime(f)) visible.add(f);
        }
        adapter.setFiles(visible);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class FileAdapter extends BaseAdapter {
        private final Context context;
        private List<File> files = Collections.emptyList();

        FileAdapter(Context context) { this.context = context; }
        void setFiles(List<File> files) { this.files = files; notifyDataSetChanged(); }
        @Override public int getCount() { return files.size(); }
        @Override public File getItem(int position) { return files.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView v = convertView instanceof TextView ? (TextView) convertView : new TextView(context);
            File f = getItem(position);
            v.setText((f.isDirectory() ? "▸  " : "    ") + f.getName());
            v.setTextSize(16);
            v.setTextColor(f.isDirectory() ? Color.rgb(241, 243, 244) : Color.rgb(220, 223, 227));
            int p = Math.round(16 * context.getResources().getDisplayMetrics().density);
            v.setPadding(p, 0, p, 0);
            v.setGravity(Gravity.CENTER_VERTICAL);
            v.setSingleLine(true);
            v.setBackgroundColor(Color.rgb(24, 26, 31));
            AbsListView.LayoutParams lp = new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.round(52 * context.getResources().getDisplayMetrics().density));
            v.setLayoutParams(lp);
            return v;
        }
    }
}
