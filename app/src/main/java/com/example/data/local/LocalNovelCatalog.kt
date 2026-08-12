package com.example.data.local

import com.example.viewmodel.DiscoveryItem
import java.net.URLEncoder

/**
 * Built-in local catalogue of web novels used for offline AI novel discovery and recommendation.
 */
object LocalNovelCatalog {

    private fun buildUrl(title: String): String {
        return "https://tomatomtl.com/#/search?search=${URLEncoder.encode(title, "UTF-8")}"
    }

    val catalog: List<DiscoveryItem> = listOf(
        DiscoveryItem(
            title = "Library of Heaven's Path",
            description = "Trope: System, Teacher, Cultivation, Comedy. A library clerk is reincarnated as an incompetent teacher with a library system that reveals the weaknesses of anything he looks at.",
            searchUrl = buildUrl("Library of Heaven's Path")
        ),
        DiscoveryItem(
            title = "Reverend Insanity",
            description = "Trope: Villain Protagonist, Time Travel, Cultivation, Ruthless, Gu Magic. A dark and gritty epic of a cultivator who travels back 500 years with the Spring Autumn Cicada to achieve immortality.",
            searchUrl = buildUrl("Reverend Insanity")
        ),
        DiscoveryItem(
            title = "Lord of the Mysteries",
            description = "Trope: Victorian Fantasy, Steampunk, Transmigration, Eldritch, Mystery. Zhou Mingrui is transmigrated into a Victorian-era world filled with potions, tarot clubs, and mystical secrets.",
            searchUrl = buildUrl("Lord of the Mysteries")
        ),
        DiscoveryItem(
            title = "Coiling Dragon",
            description = "Trope: Cultivation, Western Fantasy, Magic, Action. Linley Baruch discovers a ring carved with a dragon and begins his journey from a decaying noble family to a supreme deity.",
            searchUrl = buildUrl("Coiling Dragon")
        ),
        DiscoveryItem(
            title = "The Legendary Mechanic",
            description = "Trope: Virtual Reality, Reincarnation, Sci-Fi, System, OP Protagonist. Han Xiao is transmigrated into the game world he played, becoming a low-level NPC mechanic before the game launched.",
            searchUrl = buildUrl("The Legendary Mechanic")
        ),
        DiscoveryItem(
            title = "I Shall Seal the Heavens",
            description = "Trope: Scholar, Cultivation, Xianxia, Humor, Alchemy. Meng Hao, a failed young scholar, is forcibly recruited into a cultivation sect and rises to carve his destiny.",
            searchUrl = buildUrl("I Shall Seal the Heavens")
        ),
        DiscoveryItem(
            title = "Omniscient Reader's Viewpoint",
            description = "Trope: Survival, Post-Apocalypse, Constellations, Time Loop. Kim Dokja is the sole reader of a web novel that suddenly comes to life, forcing him to use his knowledge of the story to survive.",
            searchUrl = buildUrl("Omniscient Reader's Viewpoint")
        ),
        DiscoveryItem(
            title = "Solo Leveling",
            description = "Trope: Hunters, Leveling System, Necromancer, Action. Sung Jin-Woo, the weakest hunter of mankind, gains the unique ability to level up infinitely in a double dungeon.",
            searchUrl = buildUrl("Solo Leveling")
        ),
        DiscoveryItem(
            title = "Martial World",
            description = "Trope: Cultivation, Martial Arts, Magic Cube, Geniuses. Lin Ming obtains a mysterious Magic Cube from the Divine Realm and starts his rise to the peak of martial arts.",
            searchUrl = buildUrl("Martial World")
        ),
        DiscoveryItem(
            title = "The King's Avatar",
            description = "Trope: eSports, Gaming, MMORPG, OP Protagonist. Ye Xiu, a legendary pro player of the game Glory, is kicked from his team and starts over from a new server in an internet cafe.",
            searchUrl = buildUrl("The King's Avatar")
        ),
        DiscoveryItem(
            title = "Release That Witch",
            description = "Trope: Kingdom Building, Modern Technology, Witches, Magic, Transmigration. A mechanical engineer transmigrates into a medieval world as a prince and uses science and witches to build an industrial empire.",
            searchUrl = buildUrl("Release That Witch")
        ),
        DiscoveryItem(
            title = "A Will Eternal",
            description = "Trope: Cultivation, Xianxia, Comedy, Fear of Death. Bai Xiaochun is an endearing, coward-turned-genius cultivator whose obsession with immortality leads to hilarious misadventures.",
            searchUrl = buildUrl("A Will Eternal")
        ),
        DiscoveryItem(
            title = "Battle Through the Heavens",
            description = "Trope: Cultivation, Dou Qi, Alchemy, Ring Grandpa. Xiao Yan loses his extraordinary talent overnight, but with the help of a spirit in an old ring, he climbs back to the top.",
            searchUrl = buildUrl("Battle Through the Heavens")
        ),
        DiscoveryItem(
            title = "Overgeared",
            description = "Trope: Virtual Reality, Blacksmith, System, Gaming, Character Growth. Shin Youngwoo obtains a legendary blacksmith class in Satisfy and turns his terrible luck into overwhelming gear power.",
            searchUrl = buildUrl("Overgeared")
        ),
        DiscoveryItem(
            title = "The Second Coming of Gluttony",
            description = "Trope: Reincarnation, Regret, Dark Fantasy, Aliens. Seol Jihu receives a second chance at life in a brutal fantasy dimension called Paradise after squandering his past life.",
            searchUrl = buildUrl("The Second Coming of Gluttony")
        ),
        DiscoveryItem(
            title = "Shadow Slave",
            description = "Trope: Dark Fantasy, Nightmare Spell, Survival, Weak to Strong. Sunny is infected by the Nightmare Spell in a ruined post-apocalyptic earth and must fight through horrific nightmare worlds.",
            searchUrl = buildUrl("Shadow Slave")
        ),
        DiscoveryItem(
            title = "Chrysalis",
            description = "Trope: Monster Evolution, Ant, Dungeon, LitRPG. Anthony is reincarnated as a tiny monster ant in a massive subterranean dungeon and must lead his colony to supremacy.",
            searchUrl = buildUrl("Chrysalis")
        ),
        DiscoveryItem(
            title = "Trash of the Count's Family",
            description = "Trope: Transmigration, Slacker, Misunderstandings, Magic. Cale Henituse wakes up inside a novel as a minor trash noble and tries his best to live a peaceful, lazy life while accidentally saving the world.",
            searchUrl = buildUrl("Trash of the Count's Family")
        ),
        DiscoveryItem(
            title = "The Beginner After The End",
            description = "Trope: Reincarnation, Magic, Swordsmanship, Dragon. King Grey is reincarnated into a magical world as Arthur Leywin and seeks to protect his loved ones from ancient shadowy forces.",
            searchUrl = buildUrl("The Beginner After The End")
        ),
        DiscoveryItem(
            title = "Mother of Learning",
            description = "Trope: Time Loop, Magic Academy, Mind Magic, Mystery. Zorian is an average mage student trapped in a month-long time loop during the annual summer festival and must master magic to stop a catastrophe.",
            searchUrl = buildUrl("Mother of Learning")
        ),
        DiscoveryItem(
            title = "Desolate Era",
            description = "Trope: Cultivation, Dao, Sword, Reincarnation. Ji Ning reincarnates into a tribal world of dangerous monsters and cultivators and walks the path of the sword to protect his clan.",
            searchUrl = buildUrl("Desolate Era")
        ),
        DiscoveryItem(
            title = "Renegade Immortal",
            description = "Trope: Xianxia, Ruthless, Sword Cultivation, Revenge. Wang Lin is a mediocre young man who enters a cultivation sect and undergoes heartbreaking tragedy to walk a brutal, decisive path.",
            searchUrl = buildUrl("Renegade Immortal")
        ),
        DiscoveryItem(
            title = "Tales of Demons and Gods",
            description = "Trope: Time Travel, Reincarnation, Demon Spirits, Cultivation. Nie Li travels back in time to his childhood with knowledge of the future to save Glory City from destruction.",
            searchUrl = buildUrl("Tales of Demons and Gods")
        ),
        DiscoveryItem(
            title = "The Novel's Extra",
            description = "Trope: Transmigration, Author, Gun, System. Kim Hajin is pulled into the unfinished web novel he wrote as a background extra and must use his authorial secrets to survive.",
            searchUrl = buildUrl("The Novel's Extra")
        ),
        DiscoveryItem(
            title = "Swallowed Star",
            description = "Trope: Post-Apocalypse, Sci-Fi, Cultivation, Alien World. Luo Feng fights evolved martial monsters on a ruined earth before discovering cosmic civilizations across the stars.",
            searchUrl = buildUrl("Swallowed Star")
        ),
        DiscoveryItem(
            title = "Versatile Mage",
            description = "Trope: Magic Academy, Element Master, Urban Fantasy. Mo Fan wakes up in a familiar school where science is replaced by magic and learns he can harness multiple magical elements.",
            searchUrl = buildUrl("Versatile Mage")
        ),
        DiscoveryItem(
            title = "Cultivation Chat Group",
            description = "Trope: Modern Xianxia, Comedy, Slice of Life, Cultivation. Song Shuhang is accidentally added to a messaging group for ancient cultivators who talk about pill refining like casual chat.",
            searchUrl = buildUrl("Cultivation Chat Group")
        ),
        DiscoveryItem(
            title = "My House of Horrors",
            description = "Trope: Horror, Ghosts, System, Haunted House, Mystery. Chen Ge inherits his family's struggling haunted house and completes terrifying daily missions with real ghosts to upgrade his attractions.",
            searchUrl = buildUrl("My House of Horrors")
        ),
        DiscoveryItem(
            title = "Sovereign of the Three Realms",
            description = "Trope: Reincarnation, Alchemy, Cultivation, Revenge. Jiang Chen, son of the Heavenly Emperor, dies during a celestial calamity and is reborn in the body of a minor prince.",
            searchUrl = buildUrl("Sovereign of the Three Realms")
        ),
        DiscoveryItem(
            title = "The Greatest Estate Developer",
            description = "Trope: Transmigration, Civil Engineering, Comedy, Demons. Suho Kim is transmigrated as Lloyd Frontera, a hopeless noble, and uses civil engineering to build roads and escape debt.",
            searchUrl = buildUrl("The Greatest Estate Developer")
        ),
        DiscoveryItem(
            title = "Infinite Competitive Dungeon Society",
            description = "Trope: Dungeon, LitRPG, Spear Master, Action. Kang Shin-hyuk trains in a competitive inter-dimensional dungeon to rise above his weak mana capacity.",
            searchUrl = buildUrl("Infinite Competitive Dungeon Society")
        ),
        DiscoveryItem(
            title = "NANO MACHINE",
            description = "Trope: Wuxia, Sci-Fi, Nanotechnology, Revenge. Cheon Yeo-Woon, an illegitimate prince of the Demonic Cult, is injected with futuristic nanomachines by his descendant from the future.",
            searchUrl = buildUrl("NANO MACHINE")
        ),
        DiscoveryItem(
            title = "The World Online",
            description = "Trope: Reincarnation, Kingdom Building, History, VR Game. Ouyang Shuo returns to the launch day of the world's greatest historical VR game with ten years of future memory.",
            searchUrl = buildUrl("The World Online")
        ),
        DiscoveryItem(
            title = "Perpetual Moments",
            description = "Trope: Xianxia, Immortal Dao, Mystery, Slice of Life. A patient, slow-burn journey through immortal sects, ancient talismans, and timeless friendships.",
            searchUrl = buildUrl("Perpetual Moments")
        ),
        DiscoveryItem(
            title = "Deep Sea Embers",
            description = "Trope: Eldritch, Ghost Ship, Steampunk, Mystery, Magic. Duncan wakes up as the captain of a legendary ghost ship sailing surreal, terrifying uncharted seas.",
            searchUrl = buildUrl("Deep Sea Embers")
        ),
        DiscoveryItem(
            title = "Unscientific Beasts",
            description = "Trope: Beast Taming, Cultivation, Archaeology, Comedy. Shi Yu transmigrates into a beast-taming world with an skill index that lets him teach ridiculous skills to cute pets.",
            searchUrl = buildUrl("Unscientific Beasts")
        ),
        DiscoveryItem(
            title = "I'm Really Not The Demon God's Lackey",
            description = "Trope: Misunderstandings, Eldritch, Bookstore, Transmigration. Lin Jie runs a peaceful bookstore in a magical city and recommends normal books that customers perceive as forbidden grimoires.",
            searchUrl = buildUrl("I'm Really Not The Demon God's Lackey")
        ),
        DiscoveryItem(
            title = "The S-Classes That I Raised",
            description = "Trope: Time Travel, Hunters, Monster Raising, Caretaker. Han Yoojin regresses in time after his S-class brother's death and receives a caretaker skill to nurture new top hunters.",
            searchUrl = buildUrl("The S-Classes That I Raised")
        ),
        DiscoveryItem(
            title = "A Regressor's Tale of Cultivation",
            description = "Trope: Regression, Xianxia, Sword Dao, Heartbreaking. Seo Eun-hyun regresses every time he dies in a harsh cultivation world, preserving his martial insights across lifetimes.",
            searchUrl = buildUrl("A Regressor's Tale of Cultivation")
        ),
        DiscoveryItem(
            title = "SS-Class Suicide Hunter",
            description = "Trope: Tower Climbing, Regression, Empathy, Action. Confucius Kim gains a skill that lets him copy a murderer's skill upon dying, sending him back 24 hours.",
            searchUrl = buildUrl("SS-Class Suicide Hunter")
        )
    )

    fun all(): List<DiscoveryItem> = catalog
}
