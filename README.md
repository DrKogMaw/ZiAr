# ZiAr

ZiAr transfers files between a Windows PC and an Android phone, or directly between two Android phones, on the same Wi-Fi network. A phone can provide a mobile hotspot for the other phone. The Windows app hosts PC transfers and generates a fresh six-digit pairing code each time it starts. The Android app discovers the PC automatically, then asks for the code shown on Windows. ZiAr does not keep a shared-file library or transfer history. No cloud account is involved.

## Windows

Requires Node.js 20 or newer. In this folder, run `npm install` and `npm start`. To make a Windows installer or portable app, run `npm run dist:win`; output goes to `dist/`.

Windows may ask to permit ZiAr through the firewall. Allow it on **private networks** so the phone can discover and connect to the PC. Keep the Windows app open for transfers.

The Windows app shows currently connected devices. Choose **One device** to allow only one paired phone at a time, or **Multiple devices** to allow more. The choice is saved for the next launch. Switching from multiple to one disconnects all but the most recently active device. A device disappears from the connected list after about 20 seconds without activity, but can still send after returning from Android's file picker. Pairing expires after 12 hours without activity or when the PC app closes.

## Android

Open `android/` in Android Studio (Ladybug or newer), let it sync Gradle, and run the `app` module on Android 8.0 or newer. An Android SDK is needed to build the APK. The app searches for the Windows PC on the same Wi-Fi. Once found, enter the six-digit code displayed on Windows. If the network blocks discovery, enter one of the PC's displayed IPv4 addresses manually. Android downloads go to Downloads. Files selected on Android upload directly to the PC, and the app shows a success or error message when the transfer finishes.

### Phone to phone

Open ZiAr on both Android phones and choose **Phone to phone**. Connect both to the same Wi-Fi, or enable a hotspot on one phone and join it from the other. Keep this screen open on both. Tap the other phone under **Nearby phones**, enter the six-digit receive code shown on that phone, and select one or more files. The receiving phone saves them in `Downloads/ZiAr Received`. If discovery is blocked, enter the receiver's displayed IP address with **Send by address**. Android 8 and 9 ask for storage access before receiving files. The code changes whenever phone sharing restarts; no shared-file list or transfer history is kept.

## Usage

Use **Choose files** or drag files onto the Windows app to send them to your paired Android phone. While ZiAr is open on the phone, it automatically saves incoming files to Downloads. Files are removed from the PC queue after download, and unsent files are cleared when the Windows app closes. On Android, tap **Choose multiple files** and select several files in the system picker to send them in one batch to the PC's `Downloads/ZiAr Received` folder; click the folder path in the Windows app to open it. ZiAr shows only files currently waiting for the phone, with no history. Duplicate received filenames get a numbered suffix.

Transfers use the local network over HTTP with a session cookie and a six-digit pairing code. Use ZiAr on a trusted private Wi-Fi network. The Windows PC must remain on and reachable; transfers over the internet are not supported. Each file is limited to 1 GB.

## Tests

`npm test` runs transfer server tests.
