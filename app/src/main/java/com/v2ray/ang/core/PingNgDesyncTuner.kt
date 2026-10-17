package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem

/** Generates a broad, deterministic search space of bounded Desync variants. */
object PingNgDesyncTuner {
    enum class SearchFamily(val method: String?) {
        SPLIT(PingNgCompat.METHOD_SPLIT),
        DISORDER(PingNgCompat.METHOD_DISORDER),
        FAKE_SNI(PingNgCompat.METHOD_FAKE_SNI),
        OUT_OF_BAND(PingNgCompat.METHOD_OUT_OF_BAND),
        DISORDER_OUT_OF_BAND(PingNgCompat.METHOD_DISORDER_OUT_OF_BAND),
        ALL(null),
    }

    data class Candidate(
        val label: String,
        val arguments: String,
        val method: String = methodOf(arguments),
    )

    // The basic plan is intentionally broad too; Advanced adds ranges and
    // native switches on top of this, rather than being the only source of
    // meaningful candidates.
    private val splitPositions = listOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 24, 32, 48, 64)
    private val disorderPositions = listOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 24, 32)
    private val recordPositions = listOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 24, 32, 48, 64)
    private val fakePositions = listOf(-1, -2, -3, -4)
    private val fakeTtls = listOf(5, 7, 8, 9, 10, 12, 16)
    private val splitRanges = listOf("1-2", "1-3", "2-4", "3-6", "4-8", "8-16", "12-24")
    private val ttlRanges = listOf("5-7", "7-10", "8-12", "10-16", "16-24")
    private val delayRanges = listOf("0-1", "1-3", "1-5", "2-8", "3-12")
    private val oobTtls = listOf(0, 1, 2, 4, 8, 16)
    private val fakeSnis = listOf(
        "www.wikipedia.org",
        "www.microsoft.com",
        "www.cloudflare.com",
        "www.apple.com",
    )

    /** Returns many candidates in low-risk order; the caller stops after a stable result. */
    fun generate(
        profile: ProfileItem? = null,
        includeAdvanced: Boolean = true,
        family: SearchFamily = SearchFamily.ALL,
    ): List<Candidate> {
        val candidates = LinkedHashMap<String, Candidate>()

        fun add(label: String, options: PingNgCompat.CustomOptions) {
            val args = PingNgCompat.buildCustomArguments(options)
            candidates.putIfAbsent(args, Candidate(label, args))
        }

        splitPositions.forEach { split ->
            recordPositions.forEach { record ->
                add("split=$split, tls=$record", PingNgCompat.CustomOptions(
                    method = PingNgCompat.METHOD_SPLIT,
                    position = split.toString(),
                    tlsRecordPosition = record.toString(),
                    delayRange = "1-5",
                ))
            }
        }
        disorderPositions.forEach { disorder ->
            recordPositions.forEach { record ->
                add("disorder=$disorder, tls=$record", PingNgCompat.CustomOptions(
                    method = PingNgCompat.METHOD_DISORDER,
                    position = disorder.toString(),
                    tlsRecordPosition = record.toString(),
                    delayRange = "1-5",
                ))
            }
        }
        fakePositions.forEach { position ->
            fakeTtls.forEach { ttl ->
                fakeSnis.forEach { sni ->
                    add("fake=$position, ttl=$ttl, sni=$sni", PingNgCompat.CustomOptions(
                        method = PingNgCompat.METHOD_FAKE_SNI,
                        position = position.toString(),
                        fakeTtl = ttl.toString(),
                        // Test Fake SNI without an additional TLS-record
                        // splitter first. The old plan forced --tlsrec 1+s
                        // onto every Fake candidate, so it never tested the
                        // native Fake method in isolation.
                        tlsRecordPosition = "",
                        fakeSni = sni,
                        fakeJitter = true,
                        delayRange = "1-5",
                    ))
                }
            }
        }
        // `+s` is meaningful to the native engine: it anchors the fake split
        // to the beginning of the TLS SNI. The old plan only used negative
        // end-relative offsets, so it could miss networks that inspect the
        // first ClientHello segment instead.
        listOf("1", "2", "3", "4").forEach { sniOffset ->
            fakeSnis.forEach { sni ->
                add("fake=$sniOffset+s, sni=$sni", PingNgCompat.CustomOptions(
                    method = PingNgCompat.METHOD_FAKE_SNI,
                    position = sniOffset,
                    positionSuffix = "+s",
                    fakeTtl = "8",
                    tlsRecordPosition = "",
                    fakeSni = sni,
                    fakeJitter = true,
                    delayRange = "1-5",
                ))
            }
        }
        splitPositions.take(8).forEach { split ->
            recordPositions.take(8).forEach { record ->
                add("auto split=$split, tls=$record", PingNgCompat.CustomOptions(
                    method = PingNgCompat.METHOD_SPLIT,
                    position = split.toString(),
                    tlsRecordPosition = record.toString(),
                    automaticFallback = true,
                    delayRange = "1-5",
                ))
            }
        }

        // OOB families remain available as their own categories even when the
        // optional Advanced switch is disabled. Advanced mode adds wider
        // combinations for these same methods below.
        listOf(
            PingNgCompat.METHOD_OUT_OF_BAND,
            PingNgCompat.METHOD_DISORDER_OUT_OF_BAND,
        ).forEach { method ->
            listOf(1, 2, 4, 8).forEach { position ->
                add("$method, pos=$position", PingNgCompat.CustomOptions(
                    method = method,
                    position = position.toString(),
                    oobTtl = if (method == PingNgCompat.METHOD_DISORDER_OUT_OF_BAND) "1" else "0",
                    tlsRecordPosition = "1,3",
                    delayRange = "1-5",
                ))
            }
        }

        if (includeAdvanced) {
        // Range-based split/disorder variants cover networks where a fixed
        // packet boundary is unstable between connections.
        splitRanges.forEach { range ->
            recordPositions.forEach { record ->
                delayRanges.take(2).forEach { delay ->
                    add("split-range=$range, tls=$record, delay=$delay", PingNgCompat.CustomOptions(
                        method = PingNgCompat.METHOD_SPLIT,
                        splitRange = range,
                        tlsRecordPosition = record.toString(),
                        delayRange = delay,
                    ))
                }
            }
        }
        disorderPositions.forEach { disorder ->
            recordPositions.forEach { record ->
                delayRanges.drop(1).take(2).forEach { delay ->
                    add("disorder=$disorder, tls=$record, delay=$delay", PingNgCompat.CustomOptions(
                        method = PingNgCompat.METHOD_DISORDER,
                        position = disorder.toString(),
                        tlsRecordPosition = record.toString(),
                        delayRange = delay,
                    ))
                }
            }
        }

        // Fake and fake-TTL ranges are tested with several valid SNI values;
        // one multi-SNI candidate is included because the native engine rotates
        // through the supplied list.
        val profileSni = profile?.sni?.trim()?.takeIf {
            it.matches(Regex("[A-Za-z0-9.-]+"))
        }
        val sniVariants = (fakeSnis + "www.cloudflare.com, www.microsoft.com" + profileSni)
            .filterNotNull()
            .distinct()
        fakePositions.forEach { position ->
            ttlRanges.forEach { ttlRange ->
                recordPositions.take(8).forEach { record ->
                    sniVariants.forEach { sni ->
                        add("fake=$position, ttl=$ttlRange, tls=$record, sni=$sni", PingNgCompat.CustomOptions(
                            method = PingNgCompat.METHOD_FAKE_SNI,
                            position = position.toString(),
                            ttlRange = ttlRange,
                            tlsRecordPosition = record.toString(),
                            fakeSni = sni,
                            fakeJitter = true,
                            delayRange = "1-5",
                        ))
                    }
                }
            }
        }

        // OOB and disorder+OOB are separate native methods and must be tested
        // explicitly; they are not equivalent to Split or Disorder.
        listOf(
            PingNgCompat.METHOD_OUT_OF_BAND,
            PingNgCompat.METHOD_DISORDER_OUT_OF_BAND,
        ).forEach { method ->
            listOf(1, 2, 3, 4, 5, 8).forEach { position ->
                oobTtls.forEach { ttl ->
                    add("$method, pos=$position, oob-ttl=$ttl", PingNgCompat.CustomOptions(
                        method = method,
                        position = position.toString(),
                        oobTtl = ttl.toString(),
                        tlsRecordPosition = "1,3",
                        delayRange = "1-5",
                    ))
                }
            }
        }

        // Exercise the additional native switches exposed in the editor in a
        // small bounded tier so the search remains finite and reproducible.
        listOf(1, 2, 4, 8).forEach { fakeOffset ->
            listOf(false, true).forEach { modifyHttp ->
                add("fake advanced offset=$fakeOffset, http=$modifyHttp", PingNgCompat.CustomOptions(
                    method = PingNgCompat.METHOD_FAKE_SNI,
                    position = "-1",
                    fakeTtl = "8",
                    tlsRecordPosition = "1,3,5",
                    fakeSni = "www.cloudflare.com, www.microsoft.com",
                    fakeJitter = true,
                    modifyHttpHeaders = modifyHttp,
                    fakeOffset = fakeOffset.toString(),
                    udpFakeCount = "1",
                    dropSack = modifyHttp,
                    delayRange = "1-5",
                ))
            }
        }

        // Advanced-editor combinations. These deliberately exercise the
        // fields that are easy to miss when only Split is tested: delay and
        // TTL ranges, multiple TLS-record positions, fake data, OOB data,
        // HTTP modifiers, SACK handling and TFO. Host/port filters are bound
        // to the selected profile when their values are valid, so a search is
        // specific to that configuration rather than a generic preset.
        val targetHost = profile?.server?.trim()?.takeIf {
            it.matches(Regex("[A-Za-z0-9.-]+"))
        }.orEmpty()
        val targetPort = profile?.serverPort?.trim()?.toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?.toString()
            .orEmpty()
        val advancedDelays = listOf("0-1", "1-3", "1-5")
        val advancedRecords = listOf("1,3", "2,5", "3,8")
        val advancedFlags = listOf(false, true)
        val advancedMethods = listOf(
            PingNgCompat.METHOD_SPLIT,
            PingNgCompat.METHOD_DISORDER,
            PingNgCompat.METHOD_FAKE_SNI,
            PingNgCompat.METHOD_OUT_OF_BAND,
            PingNgCompat.METHOD_DISORDER_OUT_OF_BAND,
        )
        val oobDataVariants = listOf("", ":x", ":00", ":ff")
        val hostVariants = listOf("", targetHost).distinct()
        val portVariants = listOf("", targetPort, "443").distinct()
        advancedMethods.forEach { method ->
            advancedDelays.forEach { delay ->
                advancedRecords.forEach { records ->
                    advancedFlags.forEach { aggressive ->
                        val isFake = method == PingNgCompat.METHOD_FAKE_SNI
                        val isOob = method == PingNgCompat.METHOD_OUT_OF_BAND ||
                            method == PingNgCompat.METHOD_DISORDER_OUT_OF_BAND
                        val dataVariants = if (isOob) oobDataVariants else listOf("")
                        val scopes = dataVariants.flatMap { oobData ->
                            hostVariants.flatMap { host ->
                                portVariants.map { port -> Triple(oobData, host, port) }
                            }
                        }.distinct()
                        scopes.forEach { (oobData, host, port) ->
                            val scopeLabel = buildString {
                                append("advanced $method, tls=$records, delay=$delay")
                                if (aggressive) append(", extras")
                                if (oobData.isNotEmpty()) append(", oob=$oobData")
                                if (host.isNotEmpty()) append(", host=$host")
                                if (port.isNotEmpty()) append(", port=$port")
                            }
                            add(
                                scopeLabel,
                                PingNgCompat.CustomOptions(
                                    method = method,
                                    position = if (isFake) "-1" else "1-3",
                                    fakeTtl = "8",
                                    oobTtl = if (isOob) "1" else "0",
                                    tlsRecordPosition = records,
                                    fakeSni = if (isFake) {
                                        "www.cloudflare.com, www.microsoft.com"
                                    } else "",
                                    fakeJitter = isFake,
                                    automaticFallback = !aggressive,
                                    modifyHttpHeaders = aggressive,
                                    udpFakeCount = if (aggressive) "1" else "0",
                                    fakeOffset = if (isFake && aggressive) "2" else "0",
                                    splitRange = if (method == PingNgCompat.METHOD_SPLIT) "1-3" else "",
                                    ttlRange = if (isFake) "7-10" else "",
                                    delayRange = delay,
                                    fakeData = if (isFake && aggressive) ":00" else "",
                                    oobData = oobData,
                                    dropSack = aggressive,
                                    tcpFastOpen = aggressive,
                                    hosts = host,
                                    portFilter = port,
                                ),
                            )
                        }
                    }
                }
            }
        }
        }

        // Do not put all Split candidates first. That made the search appear to
        // test only Split and the old early-success limit could prevent the
        // other native methods from ever being reached.
        val buckets = candidates.values.groupBy { it.method }
        val methodOrder = listOf(
            PingNgCompat.METHOD_SPLIT,
            PingNgCompat.METHOD_DISORDER,
            PingNgCompat.METHOD_FAKE_SNI,
            PingNgCompat.METHOD_OUT_OF_BAND,
            PingNgCompat.METHOD_DISORDER_OUT_OF_BAND,
        )
        val result = mutableListOf<Candidate>()
        var index = 0
        while (true) {
            var added = false
            methodOrder.forEach { method ->
                buckets[method]?.getOrNull(index)?.let {
                    result += it
                    added = true
                }
            }
            if (!added) break
            index++
        }
        return if (family == SearchFamily.ALL) {
            result
        } else {
            result.filter { it.method == family.method }
        }
    }

    private fun methodOf(arguments: String): String = when {
        "--disoob" in arguments -> PingNgCompat.METHOD_DISORDER_OUT_OF_BAND
        "--oob" in arguments -> PingNgCompat.METHOD_OUT_OF_BAND
        "--fake" in arguments -> PingNgCompat.METHOD_FAKE_SNI
        "--disorder" in arguments -> PingNgCompat.METHOD_DISORDER
        else -> PingNgCompat.METHOD_SPLIT
    }
}
