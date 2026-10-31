package mv.dhivehi.subtitles.captions

/**
 * Dhivehi fonts bundled with the app. All are under the SIL Open Font License 1.1
 * (Thaana Type / Thaana Type Foundry, and Google's Noto Sans Thaana); see assets/fonts/*-OFL.txt.
 *
 * [scale] makes every font appear about the same height on screen (measured on Thaana letters).
 */
object BuiltInFonts {

    data class Font(
        val id: String,
        val name: String,
        val asset: String,
        val scale: Float,
        val variable: Boolean = false,
    )

    val ALL = listOf(
        Font("theras", "Theras", "fonts/Theras-ExtraBold.ttf", 0.95f),
        Font("bolhu", "Bolhu", "fonts/Bolhu-ExtraBold.ttf", 0.80f),
        Font("fanara", "Fanara", "fonts/Fanara-Black.ttf", 0.85f),
        Font("fanara_golhi", "Fanara Golhi", "fonts/FanaraGolhi-ExtraBold.ttf", 0.95f),
        Font("hawwa", "Hawwa", "fonts/Hawwa-Regular.ttf", 1.45f),
        Font("gurafiku", "Gurafiku", "fonts/Gurafiku-Regular.ttf", 1.60f),
        Font("haluvidhaa", "Haluvidhaa", "fonts/Haluvidhaa-Regular.ttf", 1.90f),
        Font("noto", "Noto Sans", "fonts/NotoSansThaana.ttf", 1.0f, variable = true),
    )

    const val DEFAULT_ID = "theras"

    fun byId(id: String?): Font = ALL.firstOrNull { it.id == id } ?: ALL.first()
}
