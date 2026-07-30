package com.snowball.taskwt.core

import java.nio.file.Path
import kotlin.io.path.Path

data class ApplicationPaths(
    val home: Path,
) {
    val config: Path = home.resolve("config.json")
    val logs: Path = home.resolve("logs")
    val locks: Path = home.resolve("locks")
    val temp: Path = home.resolve("temp")

    companion object {
        fun systemDefault(
            userHome: String = System.getProperty("user.home"),
        ): ApplicationPaths = ApplicationPaths(Path(userHome).resolve("TaskWorktreeManager"))
    }
}
