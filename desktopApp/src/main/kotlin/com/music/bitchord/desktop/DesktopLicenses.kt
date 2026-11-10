package com.music.bitchord.desktop

/** What this build is made of, and on what terms. */
internal data class DesktopLicense(
    val name: String,
    val license: String,
    val url: String,
    val note: String = "",
)

/** Grouped the way someone checking a licence actually reads. */
internal data class DesktopLicenseGroup(
    val title: String,
    val entries: List<DesktopLicense>,
)

internal object DesktopLicenses {

    val groups: List<DesktopLicenseGroup> = listOf(
        DesktopLicenseGroup(
            "This application",
            listOf(
                DesktopLicense(
                    "BitChord",
                    "GPL-3.0-or-later",
                    "https://www.gnu.org/licenses/gpl-3.0.html",
                    "Your music, your way.",
                ),
            ),
        ),
        DesktopLicenseGroup(
            "Audio",
            listOf(
                DesktopLicense(
                    "FFmpeg",
                    "LGPL-2.1-or-later",
                    "https://ffmpeg.org",
                    "Decoding, demuxing and resampling. The LGPL build is used " +
                        "deliberately; the GPL-enabled variant is not shipped.",
                ),
                DesktopLicense(
                    "JavaCPP / JavaCPP Presets",
                    "Apache-2.0",
                    "https://github.com/bytedeco/javacpp",
                    "The bridge from the JVM to FFmpeg.",
                ),
                DesktopLicense(
                    "ONNX Runtime",
                    "MIT",
                    "https://onnxruntime.ai",
                    "The two models Automix measures a track's tempo, beats and structure with.",
                ),
            ),
        ),
        DesktopLicenseGroup(
            "Application framework",
            listOf(
                DesktopLicense("Kotlin and kotlinx", "Apache-2.0", "https://kotlinlang.org"),
                DesktopLicense(
                    "Compose Multiplatform",
                    "Apache-2.0",
                    "https://www.jetbrains.com/lp/compose-multiplatform/",
                    "The user interface, shared with the Android application.",
                ),
                DesktopLicense(
                    "JNA",
                    "Apache-2.0 / LGPL-2.1",
                    "https://github.com/java-native-access/jna",
                    "The Windows and macOS frame bridges.",
                ),
                DesktopLicense(
                    "jsoup",
                    "MIT",
                    "https://jsoup.org",
                    "Reads the lyric providers' pages.",
                ),
                DesktopLicense(
                    "Haze",
                    "Apache-2.0",
                    "https://github.com/chrisbanes/haze",
                    "The backdrop blur behind the floating bars, as on Android.",
                ),
                DesktopLicense("SLF4J", "MIT", "https://www.slf4j.org"),
            ),
        ),
        DesktopLicenseGroup(
            "Desktop integration",
            listOf(
                DesktopLicense(
                    "dbus-java",
                    "MIT",
                    "https://github.com/hypfvieh/dbus-java",
                    "MPRIS media controls and the system tray icon and menu.",
                ),
                DesktopLicense(
                    "junixsocket",
                    "Apache-2.0",
                    "https://github.com/kohlschutter/junixsocket",
                    "Unix domain sockets for the session bus.",
                ),
            ),
        ),
    )
}
