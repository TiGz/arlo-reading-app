# Speech Recognition Setup for Fire Tablets

Arlo's collaborative reading mode uses speech recognition to let children practice reading words aloud. On Amazon Fire tablets, this requires some additional setup because Fire tablets don't include Google's speech recognition by default.

## Quick Start

1. **Install the Google App**
   - Download from APKMirror: https://www.apkmirror.com/apk/google-inc/google-search/
   - Install the APK on your Fire tablet

2. **Grant Microphone Permission to Google App**
   - Go to Settings > Apps > Google > Permissions
   - Enable "Microphone"
   - Or use ADB: `adb shell pm grant com.google.android.googlequicksearchbox android.permission.RECORD_AUDIO`

3. **Grant Microphone Permission to Arlo**
   - When prompted in Arlo, tap "Allow" for microphone access
   - Or use ADB: `adb shell pm grant com.example.arlo android.permission.RECORD_AUDIO`

4. **Run the Test**
   - Open Arlo and the setup wizard will run automatically
   - Tap "Run Test" to verify speech recognition works
   - If all checks pass, tap "Continue"

## Troubleshooting

### "ERROR_INSUFFICIENT_PERMISSIONS"
The Google app needs microphone permission. Go to Settings > Apps > Google > Permissions and enable Microphone.

### "bind to recognition service failed"
The Google app isn't installed or isn't set up correctly. Make sure you've installed the Google app (not just Google Play Services).

### Speech recognition works in test but not in the app
Make sure you granted microphone permission to Arlo itself, not just the Google app.

### No speech activities found
The Google app isn't properly installed. Try reinstalling it from APKMirror.

## ADB Commands Reference

For developers or technical users, here are the ADB commands to set everything up:

```bash
# Check if Google app is installed
adb shell pm list packages | grep googlequicksearchbox

# Grant microphone permission to Google app
adb shell pm grant com.google.android.googlequicksearchbox android.permission.RECORD_AUDIO

# Grant microphone permission to Arlo
adb shell pm grant com.example.arlo android.permission.RECORD_AUDIO

# Check granted permissions for Arlo
adb shell dumpsys package com.example.arlo | grep "RECORD_AUDIO"

# Check granted permissions for Google app
adb shell dumpsys package com.google.android.googlequicksearchbox | grep "RECORD_AUDIO"
```

## Why is this needed?

Fire tablets run a custom version of Android (Fire OS) that doesn't include Google Play Services or Google's speech recognition by default. When you sideload the Google app, it provides the speech recognition service that Arlo needs.

The permission complexity arises because:
1. Arlo needs RECORD_AUDIO to request speech recognition
2. The Google app also needs RECORD_AUDIO because it's the one actually accessing the microphone
3. On Fire OS, both permissions need to be explicitly granted

## Technical Details

Arlo uses the Android SpeechRecognizer API with an explicit ComponentName pointing to Google's recognition service:

```kotlin
val googleComponent = ComponentName(
    "com.google.android.googlequicksearchbox",
    "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
)
val recognizer = SpeechRecognizer.createSpeechRecognizer(context, googleComponent)
```

The AndroidManifest includes `<queries>` declarations required by Android 11+ for package visibility:

```xml
<queries>
    <package android:name="com.google.android.googlequicksearchbox" />
    <intent>
        <action android:name="android.speech.RecognitionService" />
    </intent>
</queries>
```

## Resetting Setup

If you need to run the setup wizard again:
1. Go to Settings > Apps > Arlo > Storage > Clear Data
2. This will reset the setup state (and all other app data)

Or use ADB:
```bash
adb shell pm clear com.example.arlo
```
