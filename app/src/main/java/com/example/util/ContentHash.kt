package com.example.util

/** MD5 fingerprint of chapter text, used to spot duplicates and changed chapters. */
object ContentHash {
    fun of(text: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
