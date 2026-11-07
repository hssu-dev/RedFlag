package dev.redflag.app

import android.os.Build

/**
 * Emulator reaches the Mac at 10.0.2.2. A real phone reaches it at localhost through
 * `adb reverse tcp:8000 tcp:8000` / `adb reverse tcp:8081 tcp:8081` (see docs/dev-log/MORNING.md).
 */
object DeviceEnv {
    val isEmulator: Boolean by lazy {
        Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator") ||
            Build.MODEL.contains("sdk_gphone") || Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")
    }
    val defaultBackendUrl: String get() = if (isEmulator) "http://10.0.2.2:8000" else "http://localhost:8000"
    val demoBaseKey: String get() = if (isEmulator) "emulator" else "host"
}
