package com.mathnote

import android.net.Uri
import java.net.URI

data class PairingConfig(val server: String, val token: String)

/** A QR carries a device credential, so parse only the exact link our desktop setup creates. */
object PairingLink {
    private val tokenPattern = Regex("[0-9a-fA-F]{32,128}")
    private val ipv4Pattern = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")

    fun parse(uri: Uri): PairingConfig? = runCatching {
        require(uri.scheme == "mathnote" && uri.encodedAuthority == "pair")
        require(uri.path.isNullOrEmpty() && uri.fragment == null)
        require(uri.queryParameterNames == setOf("server", "token"))
        require(uri.getQueryParameters("server").size == 1 && uri.getQueryParameters("token").size == 1)

        val server = uri.getQueryParameter("server") ?: error("Missing server")
        val token = uri.getQueryParameter("token") ?: error("Missing token")
        require(tokenPattern.matches(token))

        val address = URI(server)
        require(address.scheme == "http" || address.scheme == "https")
        require(!address.host.isNullOrBlank() && address.rawUserInfo == null)
        require(address.rawQuery == null && address.rawFragment == null)
        require(address.rawPath.isNullOrEmpty() || address.rawPath == "/")
        require(address.port == -1 || address.port in 1..65535)
        if (address.scheme == "http") require(isPrivateIpv4(address.host))

        PairingConfig(server.trimEnd('/'), token)
    }.getOrNull()

    private fun isPrivateIpv4(host: String): Boolean {
        if (!ipv4Pattern.matches(host)) return false
        val parts = host.split('.').map { it.toInt() }
        if (parts.any { it !in 0..255 }) return false
        return parts[0] == 10 || parts[0] == 192 && parts[1] == 168 ||
            parts[0] == 172 && parts[1] in 16..31
    }
}
