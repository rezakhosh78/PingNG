package com.v2ray.ang.core

import com.v2ray.ang.enums.EConfigType

/** Decides when MASQUE account registration must precede second-layer bootstrap startup. */
internal object WarpMasqueStartupPolicy {
    fun shouldRegisterBeforeSecondLayer(configType: EConfigType?, secondLayerEnabled: Boolean): Boolean =
        secondLayerEnabled && configType == EConfigType.WARP
}
