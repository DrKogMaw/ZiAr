# ZiAr

ZiAr transfers files between a Windows PC and an Android phone on the same Wi-Fi network. The Windows app hosts a shared folder and generates a fresh six-digit pairing code each time it starts. The Android app discovers the PC automatically, then asks for the code shown on Windows. Files stay on the PC in `Documents/ZiAr Shared` until downloaded. No cloud account is involved.

## Windows

Requires Node.js 20 or newer. In this folder, run `npm install` and `npm start`. To make a Windows installer or portable app, run `npm run dist:win`; output goes to `dist/`.

Windows may ask to permit ZiAr through the firewall. Allow it on **private networks** so the phone can discover and connect to the PC. Keep the Windows app open for transfers.

## Android

Open `android/` in Android Studio (Ladybug or newer), let it sync Gradle, and run the `app` module on Android 8.0 or newer. An Android SDK is needed to build the APK. The app searches for the Windows PC on the same Wi-Fi. Once found, enter the six-digit code displayed on Windows. If the network blocks discovery, enter one of the PC's displayed IPv4 addresses manually. Android downloads go to Downloads.

## Usage

Use **Choose files** or drag files onto the Windows app to make them available to Android. Use **Choose files** on Android to send files into the Windows shared folder. **Refresh** updates the file list on either device. Duplicate filenames are kept with a numbered suffix.

Transfers use the local network over HTTP with a session cookie and a six-digit pairing code. Use ZiAr on a trusted private Wi-Fi network. The Windows PC must remain on and reachable; transfers over the internet are not supported. Each file is limited to 1 GB.

## Tests

`npm test` runs transfer server tests.
