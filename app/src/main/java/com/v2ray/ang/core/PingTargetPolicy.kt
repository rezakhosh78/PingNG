package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem

/** MasterDNS profiles own a local bridge that must never be run by delay-test workers. */
internal fun isProfilePingable(profile: ProfileItem?): Boolean =
    profile == null || !MasterDnsBridge.isProfile(profile)

/** Retains missing profiles so the worker can report their existing invalid-config result. */
internal fun filterPingableGuids(
    guids: List<String>,
    profileForGuid: (String) -> ProfileItem?,
): List<String> = guids.filter { guid -> isProfilePingable(profileForGuid(guid)) }

internal fun isInvalidPingResult(profile: ProfileItem?, delayMillis: Long): Boolean =
    delayMillis < 0L && isProfilePingable(profile)
