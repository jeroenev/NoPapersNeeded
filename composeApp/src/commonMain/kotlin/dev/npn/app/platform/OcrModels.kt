package dev.npn.app.platform

/**
 * Text recognition's language models on this device, which first run's language step checks the
 * chosen languages against: the codes Tesseract has ([installed], null when there is no Tesseract at
 * all) and where a missing one comes from ([source]).
 */
data class OcrModels(
    val installed: Set<String>?,
    val source: OcrModelSource,
)

/** Where Tesseract and its language models come from on this device. */
enum class OcrModelSource {
    /** The app ships them itself: the Flatpak, and the Windows builds. */
    BUNDLED,

    /** Debian, Ubuntu and their kin: `tesseract-ocr-<code>`. */
    APT,

    /** Fedora and its kin: `tesseract-langpack-<code>`. */
    DNF,

    /** Arch and its kin: `tesseract-data-<code>`. */
    PACMAN,

    /** A system whose package manager the app does not know. */
    OTHER,
}

/**
 * The command that installs the models [models] through this source, with Tesseract itself when
 * [withTesseract]; null when the app knows no command (it ships them, or the system is unknown).
 */
fun OcrModelSource.installCommand(
    models: List<String>,
    withTesseract: Boolean,
): String? {
    val (install, engine, prefix) =
        when (this) {
            OcrModelSource.APT -> Triple("sudo apt install", "tesseract-ocr", "tesseract-ocr-")
            OcrModelSource.DNF -> Triple("sudo dnf install", "tesseract", "tesseract-langpack-")
            OcrModelSource.PACMAN -> Triple("sudo pacman -S", "tesseract", "tesseract-data-")
            OcrModelSource.BUNDLED, OcrModelSource.OTHER -> return null
        }
    val packages = listOfNotNull(engine.takeIf { withTesseract }) + models.map { prefix + it }
    return packages.takeIf { it.isNotEmpty() }?.joinToString(" ", prefix = "$install ")
}
