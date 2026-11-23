package app.lookout.server

import app.lookout.core.KeyValueStore
import app.lookout.core.LookoutCore
import app.lookout.core.LookoutJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest

/** An agent that paired with the phone. With an [address], its token works only from that address. */
@Serializable
data class PairedClient(val id: String, val name: String, val tokenHash: String, val address: String? = null, val createdAt: Long)

enum class Access { OWNER, CLIENT, WRONG_ADDRESS, DENIED }

/**
 * The bearer tokens, kept in a (secure) key-value store. The owner token is generated on first use
 * and shown in Setup. Each paired agent has its own token, stored as a hash.
 */
class TokenStore(private val kv: KeyValueStore) {
    @Volatile private var cached: String? = null
    private val serializer = ListSerializer(PairedClient.serializer())
    private val _clients by lazy {
        MutableStateFlow(kv.get(CLIENTS_KEY)?.let { runCatching { LookoutJson.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList())
    }
    val clients: StateFlow<List<PairedClient>> get() = _clients

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

    /** Makes a token for a paired agent and returns it. Only its hash is stored. */
    fun addClient(name: String, address: String?, now: Long): String = synchronized(this) {
        val token = LookoutCore.newToken()
        save(_clients.value + PairedClient(java.util.UUID.randomUUID().toString().take(8), name, hash(token), address, now))
        token
    }

    fun revoke(id: String) = synchronized(this) { save(_clients.value.filterNot { it.id == id }) }

    /** What [candidate] may do when it comes from the client address [remote]. */
    fun access(candidate: String?, remote: String): Access {
        if (candidate == null) return Access.DENIED
        if (matches(candidate)) return Access.OWNER
        val hash = hash(candidate)
        val client = _clients.value.firstOrNull { it.tokenHash == hash } ?: return Access.DENIED
        return if (client.address == null || Lan.parse(client.address) == Lan.parse(remote)) Access.CLIENT else Access.WRONG_ADDRESS
    }

    private fun save(clients: List<PairedClient>) {
        kv.put(CLIENTS_KEY, LookoutJson.encodeToString(serializer, clients))
        _clients.value = clients
    }

    private fun hash(token: String): String =
        java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    companion object {
        const val KEY = "token"
        const val CLIENTS_KEY = "clients"
    }
}

object Lan {
    private val TAILNET_V6 = byteArrayOf(0xfd.toByte(), 0x7a, 0x11, 0x5c, 0xa1.toByte(), 0xe0.toByte())

    /** Parses an IP literal. Nothing is resolved, so a host name gives null. */
    fun parse(ip: String): InetAddress? {
        val literal = ip.trim().removePrefix("/").removePrefix("[").removeSuffix("]").substringBefore('%')
        if (literal.isEmpty() || !(literal.contains(':') || literal.all { it.isDigit() || it == '.' })) return null
        return try { InetAddress.getByName(literal) } catch (_: Exception) { null }
    }

    /**
     * True for private (RFC 1918), link-local, loopback and IPv6 unique-local addresses.
     * The Tailscale range is unique-local, but it is not private here: [isTailnet] covers it.
     */
    fun isPrivate(ip: String): Boolean = ip.trim() == "localhost" || parse(ip)?.let(::isPrivate) == true

    fun isPrivate(addr: InetAddress): Boolean = when (addr) {
        is Inet4Address -> addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isLoopbackAddress
        is Inet6Address -> addr.isLinkLocalAddress || addr.isLoopbackAddress || ((addr.address[0].toInt() and 0xFE) == 0xFC && !isTailnet(addr))
        else -> false
    }

    /** True for the Tailscale ranges 100.64.0.0/10 and fd7a:115c:a1e0::/48. Carriers also use the IPv4 range. */
    fun isTailnet(ip: String): Boolean = parse(ip)?.let(::isTailnet) == true

    fun isTailnet(addr: InetAddress): Boolean {
        val b = addr.address
        return when (addr) {
            is Inet4Address -> (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 0x40
            is Inet6Address -> TAILNET_V6.indices.all { b[it] == TAILNET_V6[it] }
            else -> false
        }
    }

    /** The client addresses that may connect. A Tailscale address needs the "Allow Tailscale" setting. */
    fun allowsRemote(ip: String, tailscale: Boolean): Boolean = isPrivate(ip) || (tailscale && isTailnet(ip))
}
