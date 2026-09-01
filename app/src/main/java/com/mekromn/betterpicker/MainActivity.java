package com.mekromn.betterpicker;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

public final class MainActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(44), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(16, 17, 20));

        TextView title = text("Better Picker", 30, Color.rgb(241, 243, 244));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = text(
                "Automatically switches Android DocumentsUI to Modified (newest first) when the picker opens.",
                16, Color.rgb(189, 193, 198));
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(10);
        root.addView(subtitle, subLp);

        status = text("", 17, Color.rgb(241, 243, 244));
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(18), dp(18), dp(18), dp(18));
        status.setBackgroundColor(Color.rgb(34, 37, 43));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(28);
        root.addView(status, statusLp);

        Button settings = new Button(this);
        settings.setText("Accessibility settings");
        settings.setAllCaps(false);
        settings.setTextSize(16);
        settings.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        buttonLp.topMargin = dp(18);
        root.addView(settings, buttonLp);

        TextView note = text(
                "No overlay, storage permission, network permission, or navigation control. On Pixel Android 16 it uses the native More options → Sort by… → Modified (newest first) controls.",
                14, Color.rgb(154, 160, 166));
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = dp(24);
        root.addView(note, noteLp);

        return root;
    }

    private void updateStatus() {
        boolean enabled = isServiceEnabled();
        long lastSuccess = getSharedPreferences("runtime", MODE_PRIVATE)
                .getLong("last_success_ms", 0L);

        StringBuilder value = new StringBuilder(enabled
                ? "Modified sort automation  ON ✓"
                : "Modified sort automation  OFF");
        value.append("\n\nLast successful change: ");
        if (lastSuccess == 0L) {
            value.append("never");
        } else {
            value.append(DateFormat.getDateTimeInstance(
                    DateFormat.SHORT, DateFormat.MEDIUM).format(new Date(lastSuccess)));
        }
        status.setText(value.toString());
    }

    private boolean isServiceEnabled() {
        AccessibilityManager manager =
                (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String packageName = getPackageName();
        for (AccessibilityServiceInfo info : services) {
            String id = info.getId();
            if (id != null && id.contains(packageName)) return true;
        }
        return false;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
