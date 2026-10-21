package com.example.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.example.R
import java.io.File

/**
 * Typefaces bundled for long-form reading (all SIL OFL, licences in assets/licenses). Literata
 * and Lora are variable fonts, so one file covers regular and bold.
 */
object ReaderFonts {

    @OptIn(ExperimentalTextApi::class)
    private fun variable(res: Int) = FontFamily(
        Font(res, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(res, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(res, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
    )

    val Literata: FontFamily = variable(R.font.literata)
    val Lora: FontFamily = variable(R.font.lora)
    val Atkinson: FontFamily = FontFamily(
        Font(R.font.atkinson_regular, FontWeight.Normal),
        Font(R.font.atkinson_bold, FontWeight.Bold)
    )

    /** Key shown in settings to label + family. Order is the order of the chips. */
    val choices: List<Triple<String, String, FontFamily>> = listOf(
        Triple("serif", "Serif", FontFamily.Serif),
        Triple("sans", "Sans", FontFamily.SansSerif),
        Triple("mono", "Mono", FontFamily.Monospace),
        Triple("literata", "Literata", Literata),
        Triple("lora", "Lora", Lora),
        Triple("atkinson", "Atkinson", Atkinson)
    )

    fun resolve(key: String, customFontPath: String): FontFamily {
        if (key == "custom") {
            val file = File(customFontPath)
            return if (file.exists()) {
                try { FontFamily(Font(file)) } catch (e: Exception) { FontFamily.Serif }
            } else FontFamily.Serif
        }
        return choices.firstOrNull { it.first == key }?.third ?: FontFamily.Serif
    }
}
