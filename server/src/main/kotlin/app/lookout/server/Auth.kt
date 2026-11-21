package app.lookout.server

import app.lookout.core.KeyValueStore
import app.lookout.core.LookoutCore
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest

/** The bearer token. It is generated on first use and kept in a (secure) key-value store. */
class TokenStore(private val kv: KeyValueStore) {
    @Volatile private var cached: String? = null

    fun get(): String = cached ?: synchronized(this) {
        cached ?: (kv.get(KEY) ?: LookoutCore.newToken().also { kv.put(KEY, it) }).also { cached = it }
    }

    fun regenerate(): String = synchronized(this) {
        LookoutCore.newToken().also {
            kv.put(KEY, it)
            cached = it
        }
    }

    /** Constant-time comparison, so response timing leaks nothing about the token. */
    fun matches(candidate: String?): Boolean {
        if (candidate == null) return false
        return MessageDigest.isEqual(candidate.toByteArray(), get().toByteArray())
    }

    companion object { const val KEY = "token" }
}

object Lan {
    /**
     * True for private (RFC 1918), link-local, loopback and IPv6 unique-local addresses.
     * The address must be an IP literal; nothing is resolved.
     */
    fun isPrivate(ip: String): Boolean {
        val literal = ip.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
        if (literal == "localhost") return true
        if (literal.isEmpty() || !(literal.contains(':') || literal.all { it.isDigit() || it == '.' })) return false
        val addr = try { InetAddress.getByName(literal) } catch (_: Exception) { return false }
        return isPrivate(addr)
    }

    fun isPrivate(addr: InetAddress): Boolean = when (addr) {
        is Inet4Address -> addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isLoopbackAddress
        is Inet6Address -> addr.isLinkLocalAddress || addr.isLoopbackAddress || (addr.address[0].toInt() and 0xFE) == 0xFC
        else -> false
    }
}
