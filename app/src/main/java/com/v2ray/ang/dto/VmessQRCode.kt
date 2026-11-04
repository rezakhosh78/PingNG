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
    /** PingNG extension fields; older V2RayNG clients safely ignore them. */
    var png: String? = null,
    var pngargs: String? = null,
)
