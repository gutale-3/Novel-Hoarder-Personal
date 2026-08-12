package com.example.util

import java.io.IOException

/**
 * Thrown when a scraper hits a Cloudflare or DDoS protection interstitial.
 */
class CloudflareException(message: String) : IOException(message)
