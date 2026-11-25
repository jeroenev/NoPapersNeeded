package dev.npn.core.journal

/** Per-tag LWW markers; old builds ignore these setting keys. An old build deleting an empty
 * tag writes no event, so it remains kept until deleted on an upgraded device.
 */
internal object TagRetention {
    private const val PREFIX = "npn.kept-tag/"

    fun key(tag: String): String = PREFIX + tag

    fun tag(key: String): String? = key.removePrefix(PREFIX).takeIf { key.startsWith(PREFIX) }
}
