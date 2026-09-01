# Better Picker

Better Picker is a tiny rootless Android accessibility helper for the stock Android DocumentsUI file picker.

## What it does

When DocumentsUI first appears, Better Picker immediately selects the native:

**Sort by → By date modified**

Then it stops interacting with the picker.

The native DocumentsUI implementation defines this action as last-modified sorting with the newest items first.

## What it does not do

- No replacement picker UI
- No accessibility overlay
- No status-bar or navigation-bar interception
- No global navigation actions
- No storage permissions
- No file reads or writes
- No `GET_CONTENT` / `OPEN_DOCUMENT` interception
- No file provider
- No network permission
- No analytics, telemetry, Firebase, or ads

## Performance design

The accessibility service is package-scoped to:

- `com.google.android.documentsui`
- `com.android.documentsui`

It uses a zero accessibility notification timeout and first attempts stable native view IDs (`menu_sort` and `menu_sort_date`). Text matching is only a fallback. There is no filesystem scan and no UI rendering. The state machine reacts to DocumentsUI window/content events and uses short retries only if an expected native control has not materialized yet.

## Build

GitHub Actions builds the debug APK on every push.

```bash
gradle :app:assembleDebug
```

Requires JDK 17 and Android SDK platform 35.
