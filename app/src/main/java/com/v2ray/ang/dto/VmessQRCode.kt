package com.v2ray.ang.dto

data class VmessQRCode(
    var v: String = "",
    var ps: String = "",
    var add: String = "",
    var port: String = "",
    var id: String = "",
    var aid: String = "0",
    var scy: String = "",
    var net: String = "",
    var type: String = "",
    var host: String = "",
    var path: String = "",
    var tls: String = "",
    var sni: String = "",
    var alpn: String = "",
    var fp: String = "",
    var cs: String = "",
    var insecure: String = "",
    var vcn: String = "",
    var pcs: String = "",
    /** Accepted when importing VMess JSON payloads created by earlier PingNG builds. */
    var pngd: String? = null,
    /** Legacy fields retained for importing older PingNG links. */
    var png: String? = null,
    var pngargs: String? = null,
)
