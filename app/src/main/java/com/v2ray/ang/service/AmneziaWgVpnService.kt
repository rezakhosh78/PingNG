package com.v2ray.ang.service

/**
 * Runs in :AmneziaWG, separate from Xray and the delay-test services. Android
 * Go runtimes share TLS_SLOT_APP on arm64, so hiding ELF symbols alone cannot
 * safely host the two runtimes in one process.
 */
class AmneziaWgVpnService : CoreVpnService()
