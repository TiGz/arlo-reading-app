---
description: Run the Arlo Reading App on the emulator
---

1. Start the emulator (if not already running)
```bash
~/Library/Android/sdk/emulator/emulator -avd ArloEmulator -no-snapshot-load -no-snapshot-save &
```

2. Build and Install the App
// turbo
```bash
./gradlew installDebug
```

3. Launch the App
// turbo
```bash
~/Library/Android/sdk/platform-tools/adb shell am start -n com.example.arlo/.MainActivity
```
