# Better Picker

A rootless Android file-picker takeover prototype.

## Why it is built this way

Android reserves Storage Access Framework actions such as `ACTION_OPEN_DOCUMENT`,
`ACTION_CREATE_DOCUMENT`, and `ACTION_OPEN_DOCUMENT_TREE` for the system DocumentsUI.
A normal third-party APK cannot become the actual SAF broker.

Better Picker therefore uses two paths:

1. **Direct mode** for `ACTION_GET_CONTENT` / generic `ACTION_PICK`: Better Picker handles
   the intent itself and returns a grantable `content://` URI from its own provider.
2. **SAF takeover mode** for DocumentsUI: an Accessibility service detects the system
   `PickActivity`, draws a full-screen `TYPE_ACCESSIBILITY_OVERLAY`, lets the user choose
   a local path, then briefly removes the overlay and drives DocumentsUI to that same
   target. DocumentsUI remains the hidden backend that issues the real URI grant to the
   original calling app.

This is a deliberate non-root architecture. It does **not** disable DocumentsUI.
Disabling DocumentsUI would break SAF instead of creating a true third-party replacement.

## Permissions

- Accessibility service: required for automatic system-wide takeover.
- All files access: required for a full local filesystem browser on Android 11+.
- No `INTERNET` permission. No telemetry/analytics SDKs.

## Current prototype coverage

- Local shared-storage browsing.
- Search/filter inside the custom overlay.
- Direct `GET_CONTENT` selection.
- Automatic detection of AOSP/Google DocumentsUI picker activity.
- Open-document handoff through the genuine system picker.
- Folder-picker handoff (`Use this folder`).
- Create-document handoff with filename entry.

## Known prototype limitations

- Cloud DocumentsProviders are not yet rendered natively in the custom overlay. A future
  provider bridge can add first-class roots where their APIs permit it.
- DocumentsUI automation is necessarily OEM/UI-version sensitive; the driver contains
  text/view-id fallbacks and should be expanded with device-specific adapters.
- Multi-select handoff is not in the first prototype.
- Android Photo Picker (`ACTION_PICK_IMAGES`) is a separate system surface and will need
  its own takeover adapter.

## Build

The included GitHub Actions workflow builds a debug APK on push. Locally:

```bash
gradle :app:assembleDebug
```

Use JDK 17 and Android SDK platform 35.
