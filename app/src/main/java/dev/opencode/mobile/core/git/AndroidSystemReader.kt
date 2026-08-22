package dev.opencode.mobile.core.git

import java.io.File
import java.util.TimeZone
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader

/**
 * JGit assumes a desktop layout: `$HOME/.gitconfig`, `/etc/gitconfig`, and a
 * `user.home` system property it can write to. On Android `user.home` is `/`,
 * which makes config lookups fail or throw. Redirecting every config file into
 * the app's private directory keeps JGit self-contained.
 *
 * Install once from [dev.opencode.mobile.OpenCodeApp] before any JGit call.
 */
class AndroidSystemReader(private val gitHome: File) : SystemReader() {

    init {
        gitHome.mkdirs()
    }

    override fun getHostname(): String = "android"

    override fun getenv(variable: String?): String? = when (variable) {
        "HOME" -> gitHome.absolutePath
        // Stops JGit from probing /etc/gitconfig, which is unreadable here.
        "GIT_CONFIG_NOSYSTEM" -> "1"
        else -> runCatching { System.getenv(variable) }.getOrNull()
    }

    override fun getProperty(key: String?): String? = when (key) {
        "user.home" -> gitHome.absolutePath
        "user.name" -> "opencode"
        else -> runCatching { System.getProperty(key) }.getOrNull()
    }

    override fun openUserConfig(parent: Config?, fs: FS?): FileBasedConfig =
        FileBasedConfig(parent, File(gitHome, ".gitconfig"), fs)

    // Both point at files inside the sandbox. They usually do not exist, and
    // FileBasedConfig treats a missing file as an empty config.
    override fun openSystemConfig(parent: Config?, fs: FS?): FileBasedConfig =
        FileBasedConfig(parent, File(gitHome, "etc-gitconfig"), fs)

    override fun openJGitConfig(parent: Config?, fs: FS?): FileBasedConfig =
        FileBasedConfig(parent, File(gitHome, "jgit-config"), fs)

    override fun getCurrentTime(): Long = System.currentTimeMillis()

    override fun getTimezone(whenMillis: Long): Int =
        TimeZone.getDefault().getOffset(whenMillis) / (60 * 1000)

    companion object {
        fun install(filesDir: File) {
            SystemReader.setInstance(AndroidSystemReader(File(filesDir, "git-home")))
        }
    }
}
