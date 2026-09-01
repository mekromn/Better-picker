package com.mekromn.betterpicker;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;

public final class DirectPickerActivity extends Activity implements LocalFileBrowserView.Listener {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Environment.isExternalStorageManager()) {
            Toast.makeText(this, "Grant All files access to browse local storage", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        }
        LocalFileBrowserView browser = new LocalFileBrowserView(this, PickerMode.OPEN, this);
        browser.setRequestedMimeType(getIntent().getType());
        setContentView(browser);
    }

    @Override
    public void onFileSelected(File file) {
        Uri uri = BetterPickerProvider.uriFor(file);
        Intent result = new Intent();
        result.setData(uri);
        result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        setResult(RESULT_OK, result);
        finish();
    }

    @Override public void onFolderSelected(File folder) { }
    @Override public void onCreateFile(File folder, String displayName) { }

    @Override
    public void onCancel() {
        setResult(RESULT_CANCELED);
        finish();
    }
}
