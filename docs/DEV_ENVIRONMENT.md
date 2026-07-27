# ANDROID DEVELOPMENT ENVIRONMENT — FRED'S LAPTOP
# LAPTOP: LAPTOP-LAJBGU5N / Windows 11 / Git Bash

## ADB PATH
```
/c/Users/kixaz/AppData/Local/Android/Sdk/platform-tools/adb.exe
```

Add to PATH permanently in Git Bash — add this line to ~/.bashrc:
```
export PATH=$PATH:/c/Users/kixaz/AppData/Local/Android/Sdk/platform-tools
```
Then run: source ~/.bashrc
After that `adb` works directly without full path.

## COMMON ADB COMMANDS

### Install APK to connected device
```
adb install /c/Users/kixaz/Downloads/ConvoyTracker-V2-debug.apk
```

### Reinstall (keep data)
```
adb install -r /c/Users/kixaz/Downloads/ConvoyTracker-V2-debug.apk
```

### Check connected devices
```
adb devices
```

### View live app logs (convoy only)
```
adb logcat -s ConvoyRadioManager:D ConvoyMasterConfig:D ConvoyViewModel:D
```

### View all logs
```
adb logcat
```

### Uninstall convoy app
```
adb uninstall com.geeksville.mesh
```

### Push file to device
```
adb push /c/Users/kixaz/Downloads/somefile.json /sdcard/Download/
```

### Pull file from device
```
adb pull /sdcard/Download/somefile.json /c/Users/kixaz/Downloads/
```

## APK LOCATIONS

### V1 (stable)
```
/c/Users/kixaz/Downloads/ConvoyTracker-V1-debug.apk
```

### V2 (in development)
```
/c/Users/kixaz/Downloads/ConvoyTracker-V2-debug.apk
```

### Build output (universal debug)
```
/c/Users/kixaz/Meshtastic-Android/app/build/outputs/apk/fdroid/debug/app-fdroid-universal-debug.apk
```

## PYTHON PATH
```
/c/Users/kixaz/AppData/Local/Python/pythoncore-3.14-64/python.exe
```
Run as: python3

## GRADLE
Always run from project root:
```
cd /c/Users/kixaz/Meshtastic-Android
./gradlew assembleDebug
```

## USB DEBUGGING
Enable on phone: Settings > Developer Options > USB Debugging
Connect via USB cable before running adb commands.
Run `adb devices` to confirm device is recognized.
