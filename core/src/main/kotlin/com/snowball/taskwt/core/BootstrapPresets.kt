package com.snowball.taskwt.core

object BootstrapPresets {
    fun empty(): BootstrapConfig = BootstrapConfig()

    fun codeGraph(): BootstrapConfig = BootstrapConfig(
        commands = listOf(
            BootstrapCommand(
                name = "初始化 CodeGraph 索引",
                executable = "codegraph",
                arguments = listOf("init", "-i"),
                timeoutSeconds = 600,
            ),
        ),
    )
}
