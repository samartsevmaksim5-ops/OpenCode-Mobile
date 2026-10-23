package dev.opencode.mobile

import java.io.File
import java.security.SecureRandom

/**
 * One isolated OpenCode environment: its own home directory (credentials, sessions, config, MCP and plugin state)
 * and its own loopback port, so the web UI keeps a separate origin and storage per profile.
 */
data class Profile(
    val id: String,
    val name: String,
    /** The Console account (usually a Google address) signed in to this profile, once known. */
    val account: String?,
    val slot: Int,
    val createdAt: Long,
    /**
     * The port this profile's server listens on. It starts as the slot-based one (BASE_PORT + slot) and is remembered
     * once the profile had to move because something else on the phone held the port, so every user keeps a port of
     * their own across restarts.
     */
    val port: Int = Profiles.BASE_PORT + slot,
) {
    val title: String get() = account?.takeIf { it.isNotBlank() } ?: name
}

/**
 * The list of profiles and which one is active, persisted as one small JSON file. File access is injected so the
 * rules (unique slots, never deleting the last profile, home paths) can be tested without Android.
 */
class Profiles(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val items = ArrayList<Profile>()
    private var activeId: String = MAIN

    init {
        load()
    }

    val all: List<Profile> get() = synchronized(this) { items.toList() }

    val active: Profile get() = synchronized(this) { items.firstOrNull { it.id == activeId } ?: items.first() }

    fun find(id: String): Profile? = synchronized(this) { items.firstOrNull { it.id == id } }

    fun setActive(id: String): Boolean = synchronized(this) {
        if (items.none { it.id == id }) return@synchronized false

        activeId = id
        save()

        true
    }

    fun create(name: String): Profile = synchronized(this) {
        val slot = (0..MAX_PROFILES).first { candidate -> items.none { it.slot == candidate } }

        require(items.size < MAX_PROFILES) { "Too many profiles" }

        val profile = Profile(newId(), name.trim().ifEmpty { "Profile ${items.size + 1}" }.take(40), null, slot, clock())

        items += profile
        save()

        profile
    }

    fun rename(id: String, name: String) {
        update(id) { it.copy(name = name.trim().ifEmpty { it.name }.take(40)) }
    }

    fun setAccount(id: String, account: String?) {
        update(id) { it.copy(account = account?.trim()?.takeIf { value -> value.isNotEmpty() }) }
    }

    /**
     * Gives the profile a port of its own (after it had to move to the next free one) and remembers it. Remembering
     * is best effort: when the write fails the server still runs, and the next start simply picks a fresh port.
     */
    fun setPort(id: String, port: Int) {
        if (port !in MIN_PORT..MAX_PORT) return

        try {
            update(id) { it.copy(port = port) }
        } catch (_: Exception) {
            // Not worth stopping the server over.
        }
    }

    /** Removes a profile from the list. The caller deletes its files. The last profile can never be removed. */
    fun remove(id: String): Boolean = synchronized(this) {
        if (items.size <= 1 || items.none { it.id == id }) return@synchronized false

        items.removeAll { it.id == id }

        if (activeId == id) activeId = items.first().id

        save()

        true
    }

    // The account-sync thread, the service worker and the UI all touch the list (and share one .tmp file when
    // saving); a single lock keeps the list and the file consistent.
    private fun update(id: String, change: (Profile) -> Profile) = synchronized(this) {
        val index = items.indexOfFirst { it.id == id }

        if (index < 0) return@synchronized

        items[index] = change(items[index])
        save()
    }

    private fun load() {
        val root = if (file.exists()) runCatching { MiniJson.obj(file.readText()) }.getOrNull() else null
        val list = (root?.get("profiles") as? List<*>)?.mapNotNull { parse(it) }.orEmpty()

        items.clear()
        items += list.distinctBy { it.id }.distinctBy { it.slot }

        if (items.isEmpty()) {
            items += Profile(MAIN, "Main", null, 0, clock())
        }

        val wanted = root?.get("active") as? String

        activeId = if (wanted != null && items.any { it.id == wanted }) wanted else items.first().id
    }

    private fun parse(value: Any?): Profile? {
        val map = value as? Map<*, *> ?: return null
        val id = map["id"] as? String ?: return null
        val slot = (map["slot"] as? Number)?.toInt() ?: return null

        if (!ID.matches(id) || slot !in 0..MAX_PROFILES) return null

        // The port is optional: older lists predate it, and the slot-based one is the default. A port outside the
        // valid range is corruption, not a port to run on.
        val port = (map["port"] as? Number)?.toInt()?.takeIf { it in MIN_PORT..MAX_PORT } ?: (BASE_PORT + slot)

        return Profile(
            id = id,
            name = (map["name"] as? String)?.takeIf { it.isNotBlank() } ?: id,
            account = map["account"] as? String,
            slot = slot,
            createdAt = (map["createdAt"] as? Number)?.toLong() ?: 0L,
            port = port,
        )
    }

    private fun save() {
        val out = StringBuilder("{\"active\":").append(MiniJson.quote(activeId)).append(",\"profiles\":[")

        items.forEachIndexed { i, p ->
            if (i > 0) out.append(',')
            out.append("{\"id\":").append(MiniJson.quote(p.id))
            out.append(",\"name\":").append(MiniJson.quote(p.name))
            out.append(",\"account\":").append(if (p.account == null) "null" else MiniJson.quote(p.account))
            out.append(",\"slot\":").append(p.slot)
            out.append(",\"port\":").append(p.port)
            out.append(",\"createdAt\":").append(p.createdAt).append('}')
        }

        out.append("]}")
        file.parentFile?.mkdirs()

        // Write beside the target and rename, so a crash never leaves a half-written list.
        val temp = File(file.parentFile, file.name + ".tmp")

        temp.writeText(out.toString())

        if (!temp.renameTo(file)) {
            file.writeText(out.toString())
            temp.delete()
        }
    }

    companion object {
        const val MAIN = "main"
        const val BASE_PORT = 4096
        const val MAX_PROFILES = 8

        /** The range a saved profile port may fall into; anything else in the file is ignored. */
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        private val ID = Regex("^[a-z0-9]{1,16}$")

        fun newId(): String {
            val bytes = ByteArray(5)

            SecureRandom().nextBytes(bytes)

            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** `<profiles dir>/<id>/home`, mounted as `/root` inside the Linux environment. */
        fun homeDir(profilesDir: File, profile: Profile): File = File(File(profilesDir, profile.id), "home")
    }
}
