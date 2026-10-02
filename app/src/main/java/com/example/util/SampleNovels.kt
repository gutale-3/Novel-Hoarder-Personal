package com.example.util

import android.content.Context
import android.graphics.*
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.data.repository.NovelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Provides 5 complete sample novels with 500+ chapters each (520, 550, 515, 540, and 505 chapters)
 * to demonstrate and test all application functionality:
 * - Table of Contents volume subdivision and collapsible dropdowns
 * - Batch chapter division (1-50, 51-100, etc.) and continuous flat list
 * - TTS audio narration, bookmarks, progress tracking, and search
 */
object SampleNovels {

    data class NovelDefinition(
        val id: String,
        val title: String,
        val author: String,
        val category: String,
        val totalChapters: Int,
        val volumeCount: Int,
        val synopsis: String,
        val genreTheme: GenreTheme,
        val topColor: Int,
        val bottomColor: Int,
        val accentColor: Int
    )

    enum class GenreTheme {
        CULTIVATION,
        SHADOW_MONARCH,
        CYBER_ALCHEMIST,
        ARCHMAGE,
        DUNGEON_SOVEREIGN
    }

    val DEFINITIONS = listOf(
        NovelDefinition(
            id = "sample_cultivator_ascension",
            title = "The Eternal Cultivator's Ascension",
            author = "Master Lin Chen",
            category = "Reading",
            totalChapters = 520,
            volumeCount = 5,
            synopsis = "Lin Chen was once an ordinary outer disciple with crippled meridians in the Azure Cloud Sect. When an ancient dragon jade pendant awakens within his soul, his path of martial cultivation shatters all heavenly limits. From the Mortal Continent to the Divine Void Realms, follow his 520-chapter legendary journey across six great volumes.",
            genreTheme = GenreTheme.CULTIVATION,
            topColor = Color.parseColor("#0F2027"),
            bottomColor = Color.parseColor("#203A43"),
            accentColor = Color.parseColor("#00E5FF")
        ),
        NovelDefinition(
            id = "sample_shadow_monarch",
            title = "Shadow Monarch: Rebirth of the Void Emperor",
            author = "Arthur V. Pendelton",
            category = "Favorites",
            totalChapters = 550,
            volumeCount = 5,
            synopsis = "Betrayed by the High Council at the peak of the Cataclysm War, the Void Emperor wakes up ten years in the past before the Dimensional Rifts cracked open. Armed with future memories, supreme shadow extraction, and 550 chapters of relentless conquest, he rises as humanity's lone sovereign.",
            genreTheme = GenreTheme.SHADOW_MONARCH,
            topColor = Color.parseColor("#140026"),
            bottomColor = Color.parseColor("#2B1055"),
            accentColor = Color.parseColor("#D500F9")
        ),
        NovelDefinition(
            id = "sample_cyber_alchemist",
            title = "Omniscient Cyber-Alchemist",
            author = "Dr. K. Vance",
            category = "Plan to Read",
            totalChapters = 515,
            volumeCount = 5,
            synopsis = "In Neo-Avalon 2188, magic fused with high-tier cybernetics. Ryan Vance discovers a black-market neuro-core capable of decomposing any synthetic alloy, spell matrix, or neural implant into atomic elemental data. 515 chapters of corporate espionage, high-octane cyber-duels, and digital godhood.",
            genreTheme = GenreTheme.CYBER_ALCHEMIST,
            topColor = Color.parseColor("#002220"),
            bottomColor = Color.parseColor("#05443E"),
            accentColor = Color.parseColor("#00E676")
        ),
        NovelDefinition(
            id = "sample_starlight_archmage",
            title = "Supreme Archmage of the Starlight Realm",
            author = "Elena Rostova",
            category = "Reading",
            totalChapters = 540,
            volumeCount = 6,
            synopsis = "A prodigy born with dormant cosmic mana enters the imperial Astral Academy. As forgotten celestial rifts threaten reality, she masters ancient stellar constellations, forged across 540 chapters of arcane discovery, epic guild rivalries, and celestial spell warfare.",
            genreTheme = GenreTheme.ARCHMAGE,
            topColor = Color.parseColor("#1A0933"),
            bottomColor = Color.parseColor("#380036"),
            accentColor = Color.parseColor("#FFD700")
        ),
        NovelDefinition(
            id = "sample_dungeon_sovereign",
            title = "Infinite Dungeon Sovereign",
            author = "Jin Sung-Woo",
            category = "Completed",
            totalChapters = 505,
            volumeCount = 5,
            synopsis = "Trapped in the bottomless dungeon 'Tartarus Abyss' for a thousand iterations, Jin learns the hidden algorithmic code governing monster evolution and room generation. 505 chapters of strategic leveling, monster taming, and ultimate subterranean sovereignty.",
            genreTheme = GenreTheme.DUNGEON_SOVEREIGN,
            topColor = Color.parseColor("#231005"),
            bottomColor = Color.parseColor("#43220E"),
            accentColor = Color.parseColor("#FF6D00")
        )
    )

    private fun md5(input: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            input.hashCode().toString()
        }
    }

    /**
     * Seeds all 5 sample novels (500+ chapters each) into the database.
     * Generates custom covers locally in filesDir/covers/
     */
    suspend fun seedSampleNovels(
        context: Context,
        repository: NovelRepository,
        force: Boolean = false
    ): Int = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE)
        if (!force && prefs.getBoolean("sample_novels_seeded_v2", false)) {
            val existingCount = repository.getAllBooks().count { it.id.startsWith("sample_") }
            if (existingCount >= DEFINITIONS.size) {
                return@withContext existingCount
            }
        }

        var seededCount = 0

        for (def in DEFINITIONS) {
            val existing = repository.getBook(def.id)
            if (existing != null && !force) {
                seededCount++
                continue
            }

            val coverPath = generateCoverImage(context, def)
            val chapters = generateChaptersForNovel(def)

            val initialLastReadId = if (def.id == "sample_cultivator_ascension") {
                "${def.id}_ch_5"
            } else null
            val initialLastReadNum = if (def.id == "sample_cultivator_ascension") 5 else 0

            val book = BookEntity(
                id = def.id,
                url = "sample://${def.id}",
                title = def.title,
                author = def.author,
                synopsis = def.synopsis,
                coverUrl = null,
                coverLocalPath = coverPath,
                lastReadChapterId = initialLastReadId,
                lastReadChapterNumber = initialLastReadNum,
                totalChapters = chapters.size,
                category = def.category,
                updatedAt = System.currentTimeMillis()
            )

            repository.insertBookAndChapters(book, chapters)
            seededCount++
        }

        prefs.edit().putBoolean("sample_novels_seeded_v2", true).apply()
        seededCount
    }

    private fun generateChaptersForNovel(def: NovelDefinition): List<ChapterEntity> {
        val total = def.totalChapters
        val volCount = def.volumeCount
        val chaptersPerVolume = (total + volCount - 1) / volCount

        val chapters = ArrayList<ChapterEntity>(total)

        for (num in 1..total) {
            val volIndex = ((num - 1) / chaptersPerVolume) + 1
            val chapterInVol = ((num - 1) % chaptersPerVolume) + 1

            val chapterName = getSubTitle(def.genreTheme, volIndex, chapterInVol, num)
            val fullTitle = "Volume $volIndex Chapter $chapterInVol: $chapterName"
            val body = generateChapterBody(def.genreTheme, def.title, volIndex, chapterInVol, num)
            val isRead = def.id == "sample_cultivator_ascension" && num <= 5

            chapters.add(
                ChapterEntity(
                    id = "${def.id}_ch_$num",
                    bookId = def.id,
                    chapterId = "ch_$num",
                    chapterNumber = num,
                    title = fullTitle,
                    url = "sample://${def.id}/volume/$volIndex/chapter/$chapterInVol",
                    content = body,
                    hash = md5(body),
                    isRead = isRead,
                    readAt = if (isRead) System.currentTimeMillis() - (6 - num) * 3600000L else null
                )
            )
        }

        return chapters
    }

    private fun getSubTitle(genre: GenreTheme, vol: Int, chapInVol: Int, overall: Int): String {
        return when (genre) {
            GenreTheme.CULTIVATION -> {
                val prefixes = listOf("Azure Cloud", "Dragon Vein", "Spirit Lotus", "Celestial Sky", "Thunder Tribulation", "Nine Heavens", "Immortal Will", "Primordial Void", "Sword Intent", "Golden Core")
                val suffixes = listOf("Awakens", "Breakthrough", "Confrontation", "Discovery", "Enlightenment", "Domain", "Ascension", "Trial", "Destruction", "Sovereignty")
                val p = prefixes[(chapInVol + vol) % prefixes.size]
                val s = suffixes[(overall * 3 + vol) % suffixes.size]
                "$p $s"
            }
            GenreTheme.SHADOW_MONARCH -> {
                val prefixes = listOf("Echo of", "Whisper in", "Rise of", "Awakening of", "Command over", "Sovereign of", "Extraction of", "Dungeon of", "Throne of", "Emperor of")
                val nouns = listOf("the Abyss", "the Shadow Gate", "the Void Army", "the Iron Legion", "the Cataclysm", "the Undying", "the Monolith", "Darkness", "the Broken Veil", "Eternity")
                val p = prefixes[(chapInVol) % prefixes.size]
                val n = nouns[(overall + vol) % nouns.size]
                "$p $n"
            }
            GenreTheme.CYBER_ALCHEMIST -> {
                val tech = listOf("Neural Core", "Synthetic Mercury", "Transmutation Protocol", "Quantum Forge", "Bio-Alchemical Hack", "Neon Singularity", "Alloy Refinement", "Matrix Overclock", "Cyber-Elixir", "Apex Formula")
                val actions = listOf("Activated", "Decompiled", "Synthesized", "Overclocked", "Unleashed", "Calculated", "Intercepted", "Decoded", "Stabilized", "Ascended")
                val t = tech[(chapInVol + overall) % tech.size]
                val a = actions[(vol * 2 + chapInVol) % actions.size]
                "$t: $a"
            }
            GenreTheme.ARCHMAGE -> {
                val astral = listOf("Orion's Grasp", "Starlight Weave", "Astral Core", "Supernova Chant", "Cosmic Rift", "Zodiac Seal", "Nebula Cascade", "Constellation Eye", "Eclipse Incantation", "Stellar Aegis")
                val ranks = listOf("Initiation", "Resonance", "Convergence", "Singularity", "Dominion", "Transcendence", "Awakening", "Harmonics", "Revelation", "Manifestation")
                val ast = astral[(chapInVol + vol) % astral.size]
                val r = ranks[(overall + 5) % ranks.size]
                "$ast $r"
            }
            GenreTheme.DUNGEON_SOVEREIGN -> {
                val floors = listOf("Floor $overall", "Abyssal Gate", "Beast Chamber", "Titan Arena", "Crypt of Tartarus", "Labyrinth Core", "Boss Chamber", "Relic Vault", "Treasure Hall", "Nether Altar")
                val events = listOf("Conquered", "Evolution", "System Alert", "Monster Taming", "Raid Breakthrough", "Floor Clear", "Secret Room", "Dominion Claimed", "Skill Evolution", "Overlord Crowned")
                val f = floors[(chapInVol) % floors.size]
                val e = events[(overall * 7) % events.size]
                "$f — $e"
            }
        }
    }

    private fun generateChapterBody(
        genre: GenreTheme,
        novelTitle: String,
        vol: Int,
        chapInVol: Int,
        num: Int
    ): String {
        val sb = StringBuilder()
        sb.append("Volume $vol, Chapter $chapInVol (Overall Chapter $num)\n\n")

        when (genre) {
            GenreTheme.CULTIVATION -> {
                sb.append("The spiritual qi in the courtyard swirled like a descending vortex. Lin Chen sat cross-legged atop the cold limestone pedestal, feeling the ancient dragon jade pulsing rhythmically within his spiritual sea. For years, the elders of the Azure Cloud Sect had branded him a crippled youth incapable of gathering heaven and earth essence. Today, however, the golden meridian pathways inside his chest shone like incandescent celestial rivers.\n\n")
                sb.append("\"The Heavenly Dao may possess nine revolutions, but my path belongs to no mortal sect,\" Lin Chen murmured, opening his eyes. A sharp glint of sapphire lightning crackled across his irises. The surrounding bamboo grove bent in silent submission as the sheer density of his breakthrough rippled through the valley.\n\n")
                sb.append("Far in the distance, bells echoed from the Grand Elder's pavilion. The sect tournament was drawing near, and whispers of ancient ruin gates opening in the Celestial Domain had begun circulating among the inner disciples. Lin Chen exhaled a breath of turbid white air, rising to his feet with quiet conviction. His journey towards divine ascension had only just commenced.")
            }
            GenreTheme.SHADOW_MONARCH -> {
                sb.append("A cold draft swept through the shattered obsidian corridor. Arthur paused, his eyes glowing with an unmistakable violet luminescence as the System window hovered silently in the corner of his peripheral vision.\n\n")
                sb.append("[System Alert: Dimensional Barrier Breached in Sector 4.]\n[New Shadow Units Ready for Extraction: 48]\n\n")
                sb.append("\"Arise,\" Arthur commanded softly. A chill deeper than winter crept over the battlefield. From beneath the fallen armored behemoths, tendrils of pitch-black smoke twisted upward, solidifying into towering spectral knights draped in void plate armor. Each knelt before him in total, unyielding loyalty.\n\n")
                sb.append("Ten years ago, he had watched the world fall to the Monarchs of the Abyss. This time, armed with future foreknowledge and an infinite legion rising from his shadow, the timeline would bow to his sovereign command.")
            }
            GenreTheme.CYBER_ALCHEMIST -> {
                sb.append("Neon rain pattered against the reinforced polycarbonate window of Ryan Vance's underground laboratory in Sector 9. Holographic schematics for an experimental tier-5 neuro-core flickered in amber phosphor across his retina.\n\n")
                sb.append("\"Reagent temperature reaching optimal transmutation threshold,\" the neural AI chime whispered in his audio canal. Ryan picked up the vial of synthetic quicksilver, pouring it into the magnetic induction coil. Instantly, the cyber-alchemical matrix decoded the chemical bonds into raw data streams, recombining them into an exotic hyper-dense superconductor.\n\n")
                sb.append("\"The Megacorporations believe they own every line of code and every gram of magic in Neo-Avalon,\" Ryan muttered, capping the glowing compound. \"Let them try to patent what happens next.\"")
            }
            GenreTheme.ARCHMAGE -> {
                sb.append("High above the imperial city in the Astronomy Spire, Elena Rostova traced her fingers along the brass armillary sphere. Beyond the glass dome, the midnight sky was clear, studded with millions of burning stellar diamonds. Dormant cosmic mana hummed in harmony with the distant constellations.\n\n")
                sb.append("\"Starlight isn't merely ambient light,\" she recited from the ancient grimoire of the First Archmage. \"It is the primordial resonance of creation itself.\" With a subtle gesture of her hand, seven points of silver starlight aligned above her palm, weaving together into a shimmering defensive aegis.\n\n")
                sb.append("A soft knock sounded at the heavy oak doors of the observatory. The grand council had summoned her. The stellar rifts across the border were expanding, and the realm required an archmage who spoke the language of the stars.")
            }
            GenreTheme.DUNGEON_SOVEREIGN -> {
                sb.append("The damp stone walls of Tartarus Abyss vibrated with the deep, rhythmic thrumming of a floor-wide boss respawn. Jin tightened the grip on his black-iron spear, checking his status parameters.\n\n")
                sb.append("[Floor $num Progress: 100% Cleared]\n[Dungeon Authority Gained: +120 Points]\n[Available Evolutions for Tamed Monsters: 3]\n\n")
                sb.append("Behind him, a crimson-scaled Nether Drake let out a satisfied rumble, resting its massive head near Jin's shoulder. A thousand cycles in this subterranean maze had taught Jin one undeniable truth: you either become prey to the dungeon's ruthless rules, or you master its architecture until the dungeon itself obeys you.\n\n")
                sb.append("\"Next floor,\" Jin said calmly, stepping forward into the glowing portal.")
            }
        }

        return sb.toString()
    }

    /**
     * Creates a stylized, elegant cover image bitmap and writes it to disk.
     */
    private fun generateCoverImage(context: Context, def: NovelDefinition): String? {
        return try {
            val dir = File(context.filesDir, "covers").apply { mkdirs() }
            val file = File(dir, "${def.id}.png")
            if (file.exists() && file.length() > 0) {
                return file.absolutePath
            }

            val width = 360
            val height = 520
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Background Gradient
            val gradient = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                def.topColor, def.bottomColor,
                Shader.TileMode.CLAMP
            )
            val bgPaint = Paint().apply {
                shader = gradient
                isAntiAlias = true
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

            // Accent Decorative Border Frame
            val framePaint = Paint().apply {
                color = def.accentColor
                style = Paint.Style.STROKE
                strokeWidth = 3f
                isAntiAlias = true
                alpha = 180
            }
            val frameRect = RectF(16f, 16f, width - 16f, height - 16f)
            canvas.drawRoundRect(frameRect, 12f, 12f, framePaint)

            // Decorative top banner bar
            val barPaint = Paint().apply {
                color = def.accentColor
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            canvas.drawRoundRect(RectF(40f, 32f, width - 40f, 38f), 3f, 3f, barPaint)

            // Badge / Volume Indicator
            val badgePaint = Paint().apply {
                color = def.accentColor
                isAntiAlias = true
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("${def.volumeCount} VOLUMES • ${def.totalChapters} CHAPTERS", width / 2f, 65f, badgePaint)

            // Book Title (Multi-line wrap)
            val titlePaint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = true
                textSize = 26f
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }

            val words = def.title.split(" ")
            val lines = mutableListOf<String>()
            var currentLine = ""
            for (w in words) {
                val testLine = if (currentLine.isEmpty()) w else "$currentLine $w"
                if (titlePaint.measureText(testLine) > (width - 60)) {
                    if (currentLine.isNotEmpty()) lines.add(currentLine)
                    currentLine = w
                } else {
                    currentLine = testLine
                }
            }
            if (currentLine.isNotEmpty()) lines.add(currentLine)

            var startY = 180f
            for (line in lines) {
                canvas.drawText(line, width / 2f, startY, titlePaint)
                startY += 34f
            }

            // Divider line
            val dividerPaint = Paint().apply {
                color = def.accentColor
                strokeWidth = 2f
                alpha = 200
            }
            canvas.drawLine(width / 4f, startY + 10f, (width * 3f) / 4f, startY + 10f, dividerPaint)

            // Author Name
            val authorPaint = Paint().apply {
                color = Color.parseColor("#E0E0E0")
                isAntiAlias = true
                textSize = 16f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(def.author, width / 2f, startY + 45f, authorPaint)

            // Category pill at the bottom
            val pillBgPaint = Paint().apply {
                color = Color.BLACK
                alpha = 140
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            val pillRect = RectF(width / 2f - 70f, height - 70f, width / 2f + 70f, height - 40f)
            canvas.drawRoundRect(pillRect, 15f, 15f, pillBgPaint)

            val pillBorderPaint = Paint().apply {
                color = def.accentColor
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                isAntiAlias = true
            }
            canvas.drawRoundRect(pillRect, 15f, 15f, pillBorderPaint)

            val pillTextPaint = Paint().apply {
                color = def.accentColor
                isAntiAlias = true
                textSize = 13f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(def.category.uppercase(), width / 2f, height - 50f, pillTextPaint)

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            bitmap.recycle()
            file.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("SampleNovels", "Failed to generate cover image for ${def.title}", e)
            null
        }
    }
}
