package com.snowball.taskwt.cli

import com.snowball.taskwt.core.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Parameters
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.Locale
import kotlin.io.path.exists
import kotlin.system.exitProcess

private const val PARTIAL_EXIT_CODE = 4

fun main(args: Array<String>) {
    val commandLine = CommandLine(RootCommand())
    commandLine.executionExceptionHandler = CommandLine.IExecutionExceptionHandler { error, parsed, _ ->
        parsed.err.println("错误：${error.message ?: error::class.simpleName}")
        1
    }
    exitProcess(commandLine.execute(*args))
}

class RuntimeContext(
    val paths: ApplicationPaths = ApplicationPaths.systemDefault(),
    val configStore: ConfigStore = ConfigStore(paths),
    val scanner: RepositoryScanner = RepositoryScanner(),
    val manifests: ManifestStore = ManifestStore(),
    val tasks: TaskManager = TaskManager(events = JsonlEventSink(paths)),
    val tags: TagBuildService = TagBuildService(paths = paths),
    val desktop: DesktopIntegration = DesktopIntegration(),
) {
    val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun config(): AppConfig = configStore.load()

    fun repositories(config: AppConfig = config()): List<RepositoryInfo> =
        scanner.scan(
            config.scanRoots.map(Path::of),
            config.taskRoot?.let(Path::of),
        )

    fun taskDirectory(folderName: String, config: AppConfig = config()): Path {
        val root = config.taskRoot?.let(Path::of)
            ?: throw IllegalStateException("尚未配置任务根目录")
        val direct = root.resolve(TaskNaming.directoryName(folderName))
        if (direct.resolve(ManifestStore.FILE_NAME).exists()) return direct
        return manifests.list(root).firstOrNull { it.second.folderName == folderName }?.first
            ?: throw IllegalArgumentException("找不到任务：$folderName")
    }
}

@Command(
    name = "taskwt",
    mixinStandardHelpOptions = true,
    version = ["Task Worktree Manager 0.1.3"],
    description = ["多仓库 Git Worktree 与 UAT Tag 管理器"],
    subcommands = [
        SourceCommand::class,
        ServiceCommand::class,
        TaskCommand::class,
        TagCommand::class,
        ConfigCommand::class,
    ],
)
class RootCommand : Runnable {
    val context = RuntimeContext()

    override fun run() {
        CommandLine(this).usage(System.out)
    }
}

@Command(
    name = "config",
    mixinStandardHelpOptions = true,
    description = ["查看或初始化配置"],
    subcommands = [ConfigShowCommand::class, ConfigInitCommand::class],
)
class ConfigCommand : Runnable {
    @ParentCommand
    lateinit var root: RootCommand

    override fun run() = CommandLine(this).usage(System.out)
}

@Command(name = "show", description = ["显示当前配置"])
class ConfigShowCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: ConfigCommand

    override fun call(): Int {
        println(parent.root.context.json.encodeToString(parent.root.context.config()))
        return 0
    }
}

@Command(name = "init", description = ["设置首个扫描目录和任务根目录"])
class ConfigInitCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: ConfigCommand

    @Option(names = ["--scan-root"], required = true)
    lateinit var scanRoot: Path

    @Option(names = ["--task-root"], required = true)
    lateinit var taskRoot: Path

    override fun call(): Int {
        require(scanRoot.toFile().isDirectory) { "扫描目录不存在：$scanRoot" }
        require(taskRoot.toFile().isDirectory || taskRoot.toFile().mkdirs()) {
            "无法创建任务根目录：$taskRoot"
        }
        val current = parent.root.context.config()
        val updated = current.copy(
            scanRoots = listOf(scanRoot.toAbsolutePath().normalize().toString()),
            taskRoot = taskRoot.toAbsolutePath().normalize().toString(),
        )
        parent.root.context.configStore.save(updated)
        println("配置已保存：${ApplicationPaths.systemDefault().config}")
        return 0
    }
}

@Command(
    name = "source",
    mixinStandardHelpOptions = true,
    description = ["管理仓库扫描目录"],
    subcommands = [
        SourceAddCommand::class,
        SourceRemoveCommand::class,
        SourceListCommand::class,
        SourceScanCommand::class,
    ],
)
class SourceCommand : Runnable {
    @ParentCommand
    lateinit var root: RootCommand

    override fun run() = CommandLine(this).usage(System.out)
}

@Command(name = "add", description = ["添加扫描目录"])
class SourceAddCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: SourceCommand

    @Parameters(index = "0")
    lateinit var path: Path

    override fun call(): Int {
        require(path.toFile().isDirectory) { "目录不存在：$path" }
        val config = parent.root.context.config()
        val normalized = path.toAbsolutePath().normalize().toString()
        parent.root.context.configStore.save(
            config.copy(scanRoots = (config.scanRoots + normalized).distinct()),
        )
        println("已添加：$normalized")
        return 0
    }
}

@Command(name = "remove", description = ["移除扫描目录"])
class SourceRemoveCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: SourceCommand

    @Parameters(index = "0")
    lateinit var path: Path

    override fun call(): Int {
        val config = parent.root.context.config()
        val normalized = path.toAbsolutePath().normalize().toString()
        parent.root.context.configStore.save(
            config.copy(scanRoots = config.scanRoots.filterNot { Path.of(it).toAbsolutePath().normalize().toString() == normalized }),
        )
        println("已移除：$normalized")
        return 0
    }
}

@Command(name = "list", description = ["列出扫描目录"])
class SourceListCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: SourceCommand

    @Option(names = ["--json"])
    var asJson: Boolean = false

    override fun call(): Int {
        val roots = parent.root.context.config().scanRoots
        if (asJson) println(parent.root.context.json.encodeToString(roots))
        else roots.forEach(::println)
        return 0
    }
}

@Command(name = "scan", description = ["扫描全部 Git 服务并同步默认配置"])
class SourceScanCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: SourceCommand

    @Option(names = ["--json"])
    var asJson: Boolean = false

    override fun call(): Int {
        val context = parent.root.context
        val config = context.config()
        require(config.scanRoots.isNotEmpty()) { "尚未配置扫描目录" }
        val repositories = context.repositories(config)
        val services = config.services.toMutableMap()
        repositories.forEach { repository ->
            services.putIfAbsent(
                repository.id,
                ServiceConfig(
                    repositoryId = repository.id,
                    displayName = repository.name,
                    ideType = guessIde(repository),
                ),
            )
        }
        context.configStore.save(config.copy(services = services))
        if (asJson) println(context.json.encodeToString(repositories))
        else repositories.forEach { println("${it.id}\t${it.name}\t${it.rootPath}") }
        return 0
    }

    private fun guessIde(repository: RepositoryInfo): IdeType {
        val root = Path.of(repository.rootPath)
        return if (root.resolve("package.json").exists() && !root.resolve("pom.xml").exists()) {
            IdeType.WEBSTORM
        } else {
            IdeType.IDEA
        }
    }
}

@Command(
    name = "service",
    mixinStandardHelpOptions = true,
    description = ["管理服务配置"],
    subcommands = [
        ServiceListCommand::class,
        ServiceEnableCommand::class,
        ServiceDisableCommand::class,
        ServiceSetCommand::class,
        ServiceBootstrapCommand::class,
    ],
)
class ServiceCommand : Runnable {
    @ParentCommand
    lateinit var root: RootCommand

    override fun run() = CommandLine(this).usage(System.out)
}

@Command(name = "list", description = ["列出服务"])
class ServiceListCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: ServiceCommand

    @Option(names = ["--json"])
    var asJson: Boolean = false

    override fun call(): Int {
        val context = parent.root.context
        val config = context.config()
        if (asJson) {
            println(context.json.encodeToString(config.services.values.toList()))
        } else {
            config.services.values.sortedBy { it.displayName }.forEach {
                println("${it.repositoryId}\t${it.displayName}\t${it.ideType}\t${it.uatRemote}/${it.uatBranch}\t${if (it.enabled) "enabled" else "disabled"}")
            }
        }
        return 0
    }
}

abstract class ServiceToggleCommand(
    private val enabled: Boolean,
) : Callable<Int> {
    @ParentCommand
    lateinit var parent: ServiceCommand

    @Parameters(index = "0")
    lateinit var repositoryId: String

    override fun call(): Int {
        val context = parent.root.context
        val config = context.config()
        val service = config.services[repositoryId]
            ?: throw IllegalArgumentException("找不到服务：$repositoryId")
        context.configStore.save(
            config.copy(services = config.services + (repositoryId to service.copy(enabled = enabled))),
        )
        println("${service.displayName} 已${if (enabled) "启用" else "禁用"}")
        return 0
    }
}

@Command(name = "enable", description = ["启用服务"])
class ServiceEnableCommand : ServiceToggleCommand(true)

@Command(name = "disable", description = ["禁用服务"])
class ServiceDisableCommand : ServiceToggleCommand(false)

@Command(name = "set", description = ["修改服务配置"])
class ServiceSetCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: ServiceCommand

    @Parameters(index = "0")
    lateinit var repositoryId: String

    @Option(names = ["--name"])
    var name: String? = null

    @Option(names = ["--ide"])
    var ide: IdeType? = null

    @Option(names = ["--base-ref"])
    var baseRef: String? = null

    @Option(names = ["--uat-remote"])
    var uatRemote: String? = null

    @Option(names = ["--uat-branch"])
    var uatBranch: String? = null

    @Option(names = ["--initial-tag"])
    var initialTag: String? = null

    override fun call(): Int {
        val context = parent.root.context
        val config = context.config()
        val current = config.services[repositoryId]
            ?: throw IllegalArgumentException("找不到服务：$repositoryId")
        val updated = current.copy(
            displayName = name ?: current.displayName,
            ideType = ide ?: current.ideType,
            defaultBaseRef = baseRef ?: current.defaultBaseRef,
            uatRemote = uatRemote ?: current.uatRemote,
            uatBranch = uatBranch ?: current.uatBranch,
            initialUatTag = initialTag ?: current.initialUatTag,
        )
        context.configStore.save(config.copy(services = config.services + (repositoryId to updated)))
        println(context.json.encodeToString(updated))
        return 0
    }
}

@Command(
    name = "bootstrap",
    mixinStandardHelpOptions = true,
    description = ["查看或修改服务初始化配置"],
    subcommands = [ServiceBootstrapShowCommand::class, ServiceBootstrapSetCommand::class],
)
class ServiceBootstrapCommand : Runnable {
    @ParentCommand
    lateinit var parent: ServiceCommand

    override fun run() = CommandLine(this).usage(System.out)
}

@Command(name = "show", description = ["显示初始化配置 JSON"])
class ServiceBootstrapShowCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: ServiceBootstrapCommand

    @Parameters(index = "0")
    lateinit var repositoryId: String

    override fun call(): Int {
        val context = parent.parent.root.context
        val service = context.config().services[repositoryId]
            ?: throw IllegalArgumentException("找不到服务：$repositoryId")
        println(context.json.encodeToString(service.bootstrap))
        return 0
    }
}

@Command(name = "set", description = ["从 JSON 文件设置初始化配置，或启用 empty/codegraph 预设"])
class ServiceBootstrapSetCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: ServiceBootstrapCommand

    @Parameters(index = "0")
    lateinit var repositoryId: String

    @Option(names = ["--config"])
    var configFile: Path? = null

    @Option(names = ["--preset"])
    var preset: String? = null

    override fun call(): Int {
        require((configFile != null) xor (preset != null)) { "必须且只能指定 --config 或 --preset" }
        val context = parent.parent.root.context
        val appConfig = context.config()
        val service = appConfig.services[repositoryId]
            ?: throw IllegalArgumentException("找不到服务：$repositoryId")
        val bootstrap = if (configFile != null) {
            context.json.decodeFromString<BootstrapConfig>(Files.readString(configFile))
        } else {
            when (preset?.lowercase(Locale.ROOT)) {
                "empty" -> BootstrapPresets.empty()
                "codegraph" -> BootstrapPresets.codeGraph()
                else -> throw IllegalArgumentException("未知预设：$preset（支持 empty、codegraph）")
            }
        }
        val updated = service.copy(bootstrap = bootstrap)
        context.configStore.save(
            appConfig.copy(services = appConfig.services + (repositoryId to updated)),
        )
        println(context.json.encodeToString(bootstrap))
        return 0
    }
}

@Command(
    name = "task",
    mixinStandardHelpOptions = true,
    description = ["管理研发任务"],
    subcommands = [
        TaskCreateCommand::class,
        TaskAddServicesCommand::class,
        TaskListCommand::class,
        TaskPathCommand::class,
        TaskOpenCommand::class,
        TaskOpenServiceCommand::class,
        TaskTerminalCommand::class,
        TaskRevealCommand::class,
        TaskInitializeCommand::class,
        TaskRetryFailedCommand::class,
        TaskArchiveCommand::class,
        TaskDeleteCommand::class,
        TaskRestoreCommand::class,
    ],
)
class TaskCommand : Runnable {
    @ParentCommand
    lateinit var root: RootCommand

    override fun run() = CommandLine(this).usage(System.out)
}

@Command(name = "create", description = ["创建多服务 worktree 任务"])
class TaskCreateCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: TaskCommand

    @Option(names = ["--folder-name"], required = true)
    lateinit var folderName: String

    @Option(names = ["--requirement-link"], required = true)
    lateinit var requirementLink: String

    @Option(names = ["--branch"], required = true)
    lateinit var branch: String

    @Option(names = ["--services"], split = ",", required = true)
    lateinit var services: Array<String>

    @Option(names = ["--json"])
    var asJson: Boolean = false

    override fun call(): Int {
        val context = parent.root.context
        val config = context.config()
        val repositories = context.repositories(config)
        val repositoryIds = services.map { resolveServiceId(repositories, config, it) }
        val manifest = context.tasks.create(
            config,
            repositories,
            CreateTaskRequest(folderName, branch, repositoryIds, requirementLink),
        )
        if (asJson) println(context.json.encodeToString(manifest))
        else printManifestSummary(manifest)
        return if (manifest.status == WorkspaceStatus.READY) 0 else PARTIAL_EXIT_CODE
    }
}

@Command(name = "add-services", description = ["向已有任务追加服务 Worktree"])
class TaskAddServicesCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: TaskCommand

    @Option(names = ["--folder-name"], required = true)
    lateinit var folderName: String

    @Option(names = ["--service"], required = true, arity = "1..*")
    lateinit var services: Array<String>

    @Option(names = ["--json"])
    var asJson: Boolean = false

    override fun call(): Int {
        val context = parent.root.context
        val config = context.config()
        val repositories = context.repositories(config)
        val repositoryIds = services.map { resolveServiceId(repositories, config, it) }
        val taskDirectory = context.taskDirectory(folderName, config)
        val manifest = context.tasks.addServices(
            config,
            repositories,
            taskDirectory,
            AddServicesRequest(repositoryIds),
        )
        if (asJson) println(context.json.encodeToString(manifest))
        else printManifestSummary(manifest)
        return if (manifest.status == WorkspaceStatus.READY) 0 else PARTIAL_EXIT_CODE
    }
}

@Command(name = "list", description = ["列出任务"])
class TaskListCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: TaskCommand

    @Option(names = ["--json"])
    var asJson: Boolean = false

    override fun call(): Int {
        val context = parent.root.context
        val config = context.config()
        val root = config.taskRoot?.let(Path::of) ?: return 0
        val tasks = context.manifests.list(root).map { it.second }
        if (asJson) println(context.json.encodeToString(tasks))
        else tasks.forEach(::printManifestSummary)
        return 0
    }
}

@Command(name = "path", description = ["输出或复制任务路径"])
class TaskPathCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: TaskCommand

    @Parameters(index = "0")
    lateinit var folderName: String

    @Option(names = ["--copy"])
    var copy: Boolean = false

    override fun call(): Int {
        val context = parent.root.context
        val path = context.taskDirectory(folderName)
        if (copy) context.desktop.copyPath(path)
        println(path.toAbsolutePath())
        return 0
    }
}

@Command(name = "open", description = ["用 IDEA/WebStorm 打开任务"])
class TaskOpenCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: TaskCommand

    @Parameters(index = "0")
    lateinit var folderName: String

    @Option(names = ["--ide"], defaultValue = "ALL")
    lateinit var ide: String

    override fun call(): Int {
        val context = parent.root.context
        val config = context.config()
        val taskDirectory = context.taskDirectory(folderName, config)
        val manifest = context.manifests.load(taskDirectory)
        val requested = ide.uppercase()
        if (requested == "ALL" || requested == "IDEA") {
            val executable = config.ideaExecutable ?: error("尚未配置 IDEA 可执行文件")
            val directory = taskDirectory.resolve("idea-${manifest.taskDirectoryName}")
            if (directory.exists()) context.desktop.openIde(directory, executable)
        }
        if (requested == "ALL" || requested == "WEBSTORM") {
            val executable = config.webStormExecutable ?: error("尚未配置 WebStorm 可执行文件")
            val directory = taskDirectory.resolve("webstorm-${manifest.taskDirectoryName}")
            if (directory.exists()) context.desktop.openIde(directory, executable)
        }
        return 0
    }
}

@Command(name = "open-service", description = ["用对应开发工具打开任务中的单个服务"])
class TaskOpenServiceCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: TaskCommand

    @Option(names = ["--folder-name"], required = true)
    lateinit var folderName: String

    @Option(names = ["--service"], required = true)
    lateinit var service: String

    override fun call(): Int {
        val context = parent.root.context
        val config = context.config()
        val taskDirectory = context.taskDirectory(folderName, config)
        val manifest = context.manifests.load(taskDirectory)
        val workspace = manifest.services.firstOrNull {
            it.repositoryId == service || it.serviceName.equals(service, ignoreCase = true)
        } ?: throw IllegalArgumentException("任务中找不到服务：$service")
        val executable = when (workspace.ideType) {
            IdeType.IDEA -> config.ideaExecutable ?: error("尚未配置 IDEA 可执行文件")
            IdeType.WEBSTORM -> config.webStormExecutable ?: error("尚未配置 WebStorm 可执行文件")
        }
        context.desktop.openIde(Path.of(workspace.worktreePath), executable)
        return 0
    }
}

abstract class TaskPathAction : Callable<Int> {
    @ParentCommand
    lateinit var parent: TaskCommand

    @Parameters(index = "0")
    lateinit var folderName: String

    abstract fun act(context: RuntimeContext, path: Path, config: AppConfig)

    override fun call(): Int {
        val context = parent.root.context
        val config = context.config()
        act(context, context.taskDirectory(folderName, config), config)
        return 0
    }
}

@Command(name = "terminal", description = ["在任务目录打开终端"])
class TaskTerminalCommand : TaskPathAction() {
    override fun act(context: RuntimeContext, path: Path, config: AppConfig) {
        context.desktop.openTerminal(path, config.terminalExecutable)
    }
}

@Command(name = "reveal", description = ["在文件管理器中显示任务"])
class TaskRevealCommand : TaskPathAction() {
    override fun act(context: RuntimeContext, path: Path, config: AppConfig) {
        context.desktop.reveal(path)
    }
}

@Command(name = "initialize", description = ["重新运行初始化步骤"])
class TaskInitializeCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: TaskCommand

    @Parameters(index = "0")
    lateinit var folderName: String

    @Option(names = ["--failed-only"])
    var failedOnly: Boolean = false

    override fun call(): Int {
        val context = parent.root.context
        val config = context.config()
        val result = context.tasks.initialize(
            config,
            context.taskDirectory(folderName, config),
            context.repositories(config),
            failedOnly,
        )
        printManifestSummary(result)
        return if (result.status == WorkspaceStatus.READY) 0 else PARTIAL_EXIT_CODE
    }
}

@Command(name = "retry-failed", description = ["重新 checkout 失败的服务"])
class TaskRetryFailedCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: TaskCommand

    @Option(names = ["--folder-name"], required = true)
    lateinit var folderName: String

    @Option(names = ["--service"])
    var service: String? = null

    @Option(names = ["--json"])
    var asJson: Boolean = false

    override fun call(): Int {
        val context = parent.root.context
        val config = context.config()
        val taskDirectory = context.taskDirectory(folderName, config)
        val repositories = context.repositories(config)
        val repositoryIds = service?.let { listOf(resolveServiceId(repositories, config, it)) }
        val result = context.tasks.retryFailedServices(config, taskDirectory, repositories, repositoryIds)
        if (asJson) println(context.json.encodeToString(result))
        else printManifestSummary(result)
        return if (result.status == WorkspaceStatus.READY) 0 else PARTIAL_EXIT_CODE
    }
}

@Command(name = "archive", description = ["归档并移除任务 worktree"])
class TaskArchiveCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: TaskCommand

    @Parameters(index = "0")
    lateinit var folderName: String

    @Option(names = ["--force-confirm"], description = ["有风险时必须再次输入完整 folderName"])
    var forceConfirm: String? = null

    override fun call(): Int {
        val context = parent.root.context
        val config = context.config()
        val taskDirectory = context.taskDirectory(folderName, config)
        val force = forceConfirm != null
        require(!force || forceConfirm == folderName) { "--force-confirm 必须与 folderName 完全一致" }
        printManifestSummary(
            context.tasks.archive(config, taskDirectory, context.repositories(config), force),
        )
        return 0
    }
}

@Command(name = "delete", description = ["永久删除任务目录与 worktree（保留 feature 分支）"])
class TaskDeleteCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: TaskCommand

    @Option(names = ["--folder-name"], required = true)
    lateinit var folderName: String

    @Option(names = ["--force-discard"], description = ["确认丢弃未提交改动后删除"])
    var forceDiscard: Boolean = false

    override fun call(): Int {
        val context = parent.root.context
        val taskDirectory = context.taskDirectory(folderName)
        context.tasks.delete(taskDirectory, forceDiscard)
        println("已删除任务：$folderName")
        return 0
    }
}

@Command(name = "restore", description = ["恢复已归档任务"])
class TaskRestoreCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: TaskCommand

    @Parameters(index = "0")
    lateinit var folderName: String

    @Option(names = ["--skip-bootstrap"])
    var skipBootstrap: Boolean = false

    override fun call(): Int {
        val context = parent.root.context
        val config = context.config()
        val result = context.tasks.restore(
            config,
            context.taskDirectory(folderName, config),
            context.repositories(config),
            rerunBootstrap = !skipBootstrap,
        )
        printManifestSummary(result)
        return if (result.status == WorkspaceStatus.READY) 0 else PARTIAL_EXIT_CODE
    }
}

@Command(
    name = "tag",
    mixinStandardHelpOptions = true,
    description = ["预检与生成 UAT Tag"],
    subcommands = [
        TagPreflightCommand::class,
        TagBuildCommand::class,
        TagRetryCommand::class,
        TagHistoryCommand::class,
    ],
)
class TagCommand : Runnable {
    @ParentCommand
    lateinit var root: RootCommand

    override fun run() = CommandLine(this).usage(System.out)
}

@Command(name = "preflight", description = ["预览合并、提交和预计 Tag"])
class TagPreflightCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: TagCommand

    @Option(names = ["--folder-name"], required = true)
    lateinit var folderName: String

    @Option(names = ["--service"], required = true)
    lateinit var repositoryId: String

    @Option(names = ["--json"])
    var asJson: Boolean = false

    override fun call(): Int {
        val context = parent.root.context
        val config = context.config()
        val preview = context.tags.preflight(
            config,
            context.taskDirectory(folderName, config),
            repositoryId,
        )
        if (asJson) {
            println(context.json.encodeToString(preview))
        } else {
            println(
                """
            |服务：${preview.serviceName}
            |特性分支：${preview.featureBranch}@${preview.featureSha}
            |测试分支：${preview.remote}/${preview.testBranch}@${preview.testSha}
            |合并方式：${preview.mergeMode}
            |预计 Tag：${preview.estimatedTag}
            |远端同步：ahead=${preview.featureSync.ahead}, behind=${preview.featureSync.behind}
            |
            |提交：
            |${preview.commitList.joinToString("\n")}
            |
            |Diff：
            |${preview.diffStat}
                """.trimMargin(),
            )
        }
        return 0
    }
}

@Command(name = "build", description = ["合并测试分支并推送 UAT Tag（内部自动预检）"])
class TagBuildCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: TagCommand

    @Option(names = ["--folder-name"], required = true)
    lateinit var folderName: String

    @Option(names = ["--services"], split = ",", required = true)
    lateinit var services: Array<String>

    @Option(names = ["--json"])
    var asJson: Boolean = false

    override fun call(): Int {
        val context = parent.root.context
        val config = context.config()
        val taskDirectory = context.taskDirectory(folderName, config)
        val results = services.map { context.tags.build(config, taskDirectory, it) }
        if (asJson) println(context.json.encodeToString(results))
        else results.forEach { println(it.message ?: "${it.serviceName}：${it.state}") }
        return if (results.all { it.state == TagOperationState.SUCCESS }) 0 else PARTIAL_EXIT_CODE
    }
}

@Command(name = "retry", description = ["恢复 PARTIAL Tag 操作"])
class TagRetryCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: TagCommand

    @Option(names = ["--folder-name"], required = true)
    lateinit var folderName: String

    @Option(names = ["--operation"], required = true)
    lateinit var operationId: String

    @Option(names = ["--json"])
    var asJson: Boolean = false

    override fun call(): Int {
        val context = parent.root.context
        val config = context.config()
        val result = context.tags.resumePartial(
            config,
            context.taskDirectory(folderName, config),
            operationId,
        )
        if (asJson) println(context.json.encodeToString(result))
        else println(result.message ?: result.state)
        return if (result.state == TagOperationState.SUCCESS) 0 else PARTIAL_EXIT_CODE
    }
}

@Command(name = "history", description = ["列出任务 Tag 操作历史"])
class TagHistoryCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: TagCommand

    @Option(names = ["--folder-name"], required = true)
    lateinit var folderName: String

    override fun call(): Int {
        val context = parent.root.context
        val directory = context.taskDirectory(folderName)
        val history = TagOperationStore().list(directory)
        println(context.json.encodeToString(history))
        return 0
    }
}

private fun printManifestSummary(manifest: TaskManifest) {
    println("${manifest.folderName}\t${manifest.featureBranch}\t${manifest.status}")
    manifest.services.forEach {
        println("  ${it.serviceName}\t${it.status}\t${it.worktreePath}")
        it.warnings.forEach { warning -> println("    警告：$warning") }
    }
}

fun resolveServiceId(
    repositories: List<RepositoryInfo>,
    config: AppConfig,
    idOrName: String,
): String {
    repositories.firstOrNull { it.id == idOrName }?.id?.let { return it }
    repositories.firstOrNull { it.name.equals(idOrName, ignoreCase = true) }?.id?.let { return it }
    config.services.values.firstOrNull {
        it.repositoryId == idOrName || it.displayName.equals(idOrName, ignoreCase = true)
    }?.repositoryId?.let { return it }
    throw IllegalArgumentException("找不到服务：$idOrName")
}
