package com.example.data.sync

import java.net.HttpURLConnection
import java.net.URL

/** The Inkvault sync service: encrypted named blobs in the password's mailbox. */
class CloudStore(private val baseUrl: String, private val crypto: SyncCrypto) {

    class WrongPasswordException : Exception("This sync password is already used with different data")

    private fun open(name: String, method: String): HttpURLConnection =
        (URL("${baseUrl.trimEnd('/')}/v1/${crypto.space}/$name").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("X-Sync-Token", crypto.token)
        }

    /** The decrypted text, or null when nothing is stored under [name] yet. */
    fun read(name: String): String? {
        val c = open(name, "GET")
        try {
            return when (c.responseCode) {
                200 -> crypto.decrypt(c.inputStream.bufferedReader().use { it.readText() })
                404 -> null
                403 -> throw WrongPasswordException()
                else -> throw java.io.IOException("Sync service answered ${c.responseCode}")
            }
        } finally { c.disconnect() }
    }

    fun write(name: String, text: String) {
        val c = open(name, "PUT")
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            c.outputStream.use { it.write(crypto.encrypt(text).toByteArray(Charsets.UTF_8)) }
            when (c.responseCode) {
                in 200..299 -> {}
                403 -> throw WrongPasswordException()
                else -> throw java.io.IOException("Sync service answered ${c.responseCode}")
            }
        } finally { c.disconnect() }
    }
}
