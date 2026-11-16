# MY SHARE

A first Android MVP for local-network file transfer without internet.

## Build

Open this repository in GitHub and run the **Build MY SHARE APK** workflow (or push to `main`). Download the `MY-SHARE-debug-apk` artifact.

## How to use

1. Install MY SHARE on both Android devices.
2. Connect both devices to the same Wi-Fi network, or enable hotspot on the receiving device and connect the sending device to it.
3. On the receiving device tap **TERIMA**. Keep the app open; it displays a QR code and starts the local receiver.
4. On the sending device tap **KIRIM**, scan the QR code, choose one or more files, and wait for transfer completion.
5. Received files are stored in the app-specific `Download/MyShare` folder.

## Notes

- Internet is not used for file transfer. Both devices must be on the same local Wi-Fi/hotspot network.
- This is an MVP: keep the receiving app open during transfer. Some Android hotspot implementations isolate clients or do not expose the hotspot IP to apps; if that happens, use a normal Wi-Fi router or adjust the IP discovery for that device.
- This build uses Android's document picker, so no broad storage permission is required.
