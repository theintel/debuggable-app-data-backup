# debuggable-app-data-backup

This application can backup and restore private app data of **debuggable apps** using Shizuku without root. This includes credential-encrypted app data (`/data/data/{pkg}`) and device-encrypted app data (`/data/user_de/0/{pkg}`).
Exported data will be saved to `/sdcard/AppData`.

See [releases](https://github.com/timschneeb/debuggable-app-data-backup/releases) for downloads.

## Backup modes

The app picks the mode per app, and shows it next to the package name:

| Mode | Applies to | How it works |
|------|------------|--------------|
| `run-as` | Apps marked as debuggable | Copies the private data directories directly, as described above. Output: `/sdcard/AppData/{pkg}/private_data` and `private_data_de`. |
| `ADB backup` | Non-debuggable apps with `android:allowBackup="true"` in their manifest | Uses the system `bu` tool through Shizuku, the same mechanism as `adb backup` / `adb restore`. Output: `/sdcard/AppData/{pkg}/backup.ab`. |

Notes on ADB backup mode:
- Android shows a confirmation screen on the device for every backup and restore. You have to accept it there, otherwise the operation waits.
- Starting with Android 12, ADB backups of non-debuggable apps that target API 31 or higher no longer include app data. These apps are labelled as such in the list, and the app warns you if the resulting backup is empty.
- Only the app's data is backed up (no APK, OBB or shared storage), and apps that only use key-value backup agents are not covered.
- The *Clean restore* option only applies to the `run-as` mode.
- System apps are hidden from the list unless they are debuggable. Use *Show all apps* to list everything.

## Debuggable apps

Again, the `run-as` mode only applies to apps marked as debuggable. My personal use case for this was in combination with a system-level exploit that allowed me to execute arbitrary code as Android's `system_server`. Using its privileges, it was possible to rewrite Android's package manifest caches and trick the system into believing that any app was debuggable. As a consequence, it allowed any apps private data to be read and written using `run-as` or this tool.


<img src="screenshot.png" width="300"/>