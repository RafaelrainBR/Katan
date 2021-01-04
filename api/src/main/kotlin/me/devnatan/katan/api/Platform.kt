package me.devnatan.katan.api

/**
 * Represents the platform in which Katan is running.
 * @property os operating system information.
 */
data class Platform(val os: OS) {

    /**
     * Platform operational system info.
     * @property name the name of the operating system.
     * @property arch the operating system architecture information.
     * @property version the operating system version.
     */
    data class OS(val name: String, val arch: String, val version: String) {

        override fun toString(): String {
            return "$name v$version ($arch)"
        }

    }

    override fun toString() = os.toString()

}

/**
 * Returns `true` if the operating system is Windows.
 * @see Platform.OS
 */
fun Platform.isWindows(): Boolean {
    return os.name.toLowerCase().indexOf("windows") >= 0
}

/**
 * Returns `true` if the operating system is Linux.
 * @see Platform.OS
 */
fun Platform.isLinux(): Boolean {
    return os.name.toLowerCase().indexOf("linux") >= 0
}

/**
 * Returns `true` if the operating system is macOS.
 * @see Platform.OS
 */
fun Platform.isMac(): Boolean {
    val name = os.name.toLowerCase()
    return name.startsWith("mac") || name.startsWith("darwin")
}

/**
 * Returns `true` if the operating system is Unix.
 * @see Platform.OS
 */
fun Platform.isUnix(): Boolean {
    val name = os.name.toLowerCase()
    if (name.indexOf("sunos") >= 0 || name.indexOf("linux") >= 0)
        return true

    if (isMac() && os.version.startsWith("10."))
        return true

    return false
}

/**
 * Detects the current platform based on the system properties.
 * @see System.getProperty
 */
fun currentPlatform() = Platform(
    Platform.OS(
        System.getProperty("os.name"),
        System.getProperty("os.arch"),
        System.getProperty("os.version", "unknown")
    )
)

/**
 * Clears the console screen according to the current platform.
 */
fun Platform.clearConsole() {
    if (isWindows()) ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
    else Runtime.getRuntime().exec("clear");
}