# Third-party notices

Original WearBridge code is licensed under GNU GPL version 3 (see `LICENSE`). WearBridge also includes or derives from the following third-party components. Their listed licenses apply to those components and files and remain in force alongside the project license.

| Component | Use in WearBridge | License / notice |
|---|---|---|
| Android Wear OS Data Layer sample | Initial phone/watch sample structure and files retaining Android copyright headers | Apache License 2.0; https://github.com/android/wear-os-samples |
| Shizuku API and provider | Watch-side Shizuku integration (`dev.rikka.shizuku:api`, `provider`) | MIT; https://github.com/RikkaApps/Shizuku-API |
| Kotlin Coroutines | Async phone and watch code (`kotlinx-coroutines-*`) | Apache License 2.0; https://github.com/Kotlin/kotlinx.coroutines |
| Google Play services Wearable | Wear Data Layer APIs (`play-services-wearable`) | Google Play services terms; https://developers.google.com/android/guides/overview |
| Android Gradle Plugin, Kotlin Gradle plugin, and Gradle wrapper | Build tooling | Respective upstream licenses; these are build tools, not app code |

`BridgeTerminalView`, the terminal display/input implementation, is project code; no third-party terminal emulator library source is included. The project is licensed under GPL-3.0. UI/UX skills and documentation were used as design guidance, not copied code.

Preserved Apache copyright/license headers in derived files are the authoritative per-file notices. For exact dependency versions, see the module `build.gradle.kts` files.
