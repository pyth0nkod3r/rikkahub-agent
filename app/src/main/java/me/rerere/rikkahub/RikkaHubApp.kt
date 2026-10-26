package me.rerere.rikkahub

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderSetting
import me.rerere.common.android.Logging
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.browser.HeadlessBrowserSessionPool
import me.rerere.rikkahub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkahub.data.ai.consolidationModel
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.remote.ModelsDevUpdater
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.data.sync.RestoreFailedException
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.di.viewModelModule
import me.rerere.rikkahub.service.MediaCreationService
import me.rerere.rikkahub.service.WebServerService
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.DatabaseUtil
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.WorkspaceManager
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin
import java.io.File

private const val TAG = "RikkaHubApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"
const val WEB_SERVER_NOTIFICATION_CHANNEL_ID = "web_server"
const val MEDIA_CREATION_NOTIFICATION_CHANNEL_ID = "media_creation"
const val MEMORY_DREAM_NOTIFICATION_CHANNEL_ID = "memory_dream"

class RikkaHubApp : Application(), SingletonImageLoader.Factory {
    /**
     * Coil 3 singleton initialization. Implementing [SingletonImageLoader.Factory] on the
     * Application class guarantees the loader is created exactly once, before any Compose
     * view, FloatingWindow, or onTrimMemory callback can race with
     * setSingletonImageLoaderFactory (which crashed with IllegalStateException when
     * ChatFloatingWindows or activity recreation accessed Coil first).
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .crossfade(true)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.10)
                    .build()
            }
            .components {
                add(OkHttpNetworkFetcherFactory(
                    callFactory = { get<okhttp3.OkHttpClient>() },
                    cacheStrategy = { CacheControlCacheStrategy() },
                ))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory(scaleToDensity = true))
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        // Restore files and settings before eager Koin singletons or workers can access them.
        // Skip runBlocking(Dispatchers.IO) completely on normal cold starts when no restore is staged.
        if (BackupManager.hasPendingRestore(this)) {
            try {
                val restored = runBlocking(Dispatchers.IO) {
                    BackupManager.applyPendingRestore(this@RikkaHubApp, JsonInstant)
                }
                if (restored) {
                    Toast.makeText(this, R.string.backup_page_restore_success, Toast.LENGTH_LONG).show()
                }
            } catch (e: RestoreFailedException) {
                Log.e(TAG, "Backup restore rolled back", e)
                Toast.makeText(this, "备份恢复失败，已保留原数据。请重新导入备份。", Toast.LENGTH_LONG).show()
            }
        }

        // :ai (and other sub-:app modules) have no BuildConfig of their own, so this is
        // how their provider code learns whether it's running a debug build — needed to
        // gate full request/response body logging the same way HttpLoggingInterceptor
        // is already gated behind BuildConfig.DEBUG in DataSourceModule.
        Logging.setDebugLoggingEnabled(BuildConfig.DEBUG)
        startKoin {
            androidLogger()
            androidContext(this@RikkaHubApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }
        this.createNotificationChannel()

        // Restore any headless conversation IDs that survived a process kill; must run
        // before any cron worker fires so mark/unmark are consistent.
        HeadlessConversations.init(this)

        // Sweep orphan headless conversations created by workers that were killed mid-execute.
        sweepOrphanHeadlessConversations()

        // set cursor window size to 4MB (balanced for chat history without bloating native heap)
        DatabaseUtil.setCursorWindowSize(4 * 1024 * 1024)

        // install crash handler
        CrashHandler.install(this)

        // Defer non-critical filesystem and background maintenance tasks so the main thread
        // renders the UI instantly without CPU/IO contention or device lag
        scheduleDeferredMaintenance()

        // Load models.dev data for model capability matching, then refresh it in background
        updateModelsDev()

        // Start WebServer if enabled in settings
        startWebServerIfEnabled()

        // Resume media generations interrupted by the last process death
        resumeMediaCreations()
        resumeMemoryConsolidation()

        // Start Telegram bot if previously enabled — service is START_NOT_STICKY so OS won't
        // auto-revive it after a process kill; we need to bring it back ourselves.
        startTelegramBotIfEnabled()

        // Initialise the agent's `~` workspace at /data/data/<pkg>/files/workspace/.
        // Tools resolve `~` and `~/foo` paths to this dir, giving the LLM a stable
        // sandbox for `.learnings/`, scratch files, and skill state without scoped-
        // storage friction. Termux-style: private, persistent, OS-blessed.
        me.rerere.rikkahub.data.ai.tools.local.AgentWorkspace.init(this)

        // Increment launch count
        incrementLaunchCount()

        // Phase 12: kick off the workflow trigger registry. It subscribes to the workflows
        // table and reconciles broadcast receivers / geofences / time_cron schedules with
        // every change. With zero enabled workflows, no receivers are registered.
        startWorkflowRegistry()

        // Phase-17 stability — register a network-change monitor that evicts OkHttp's
        // connection pool on every default-network transition. Fixes the post-Termux-
        // interactive-session "Unable to resolve host …" bug: when the user opens
        // Termux's terminal for `htop` the app backgrounds, Android may flip the
        // network into a restricted state, and the JVM's negative DNS cache plus
        // OkHttp's idle sockets keep the failure sticky after return. Eviction on the
        // next onAvailable forces a fresh DNS lookup + new socket on the next request.
        startNetworkChangeMonitor()

        // Phase 24 — unified AgentRun ledger boot recovery. Walk the ledger once per
        // process start: any autonomous run (cron / workflow / sub-agent / Telegram /
        // external automation) left in flight by a killed process is flipped to
        // `process_lost` and a single aggregate notification is fired. This is the
        // cross-pillar generalisation of the Phase 9.5 cron stranded-row sweep and is what
        // makes background sub-agents survivable across process death.
        runAgentRunBootRecovery()

        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            SingletonImageLoader.get(this).memoryCache?.let { cache ->
                if (level >= TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_MODERATE) {
                    cache.clear()
                } else if (level >= TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_RUNNING_LOW) {
                    cache.trimToSize(cache.size / 2)
                }
            }
        } catch (_: Throwable) {}

        try {
            if (level >= TRIM_MEMORY_RUNNING_LOW || level >= TRIM_MEMORY_UI_HIDDEN) {
                SQLiteDatabase.releaseMemory()
                evictIdleHeavyRuntimes(forceBrowserEvict = level >= TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_BACKGROUND)
            }
        } catch (_: Throwable) {}

        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            System.gc()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        try {
            SingletonImageLoader.get(this).memoryCache?.clear()
        } catch (_: Throwable) {}
        try {
            SQLiteDatabase.releaseMemory()
            evictIdleHeavyRuntimes(forceBrowserEvict = true)
        } catch (_: Throwable) {}
        System.gc()
    }

    private fun evictIdleHeavyRuntimes(forceBrowserEvict: Boolean) {
        runCatching { HeadlessBrowserSessionPool.evictOnMemoryPressure(forceAll = forceBrowserEvict) }
        runCatching {
            get<AppScope>().launch(Dispatchers.IO) {
                runCatching { get<me.rerere.locallm.litert.LiteRtRuntime>().unloadIfIdle() }
                runCatching { get<me.rerere.llamacpp.LlamaCppRuntime>().unloadIfIdle() }
            }
        }
    }

    /**
     * Inspect the package's recent ApplicationExitInfo records for a native crash whose
     * stack/description points at a local-runtime JNI library. When one is found, set the
     * matching runtime's force-CPU flag so the next inference runs on CPU, and record the
     * crashed accelerator label so the settings UI can surface a "switched to CPU" notice.
     *
     * Best-effort: errors are logged, never thrown — a stuck app start is worse than a
     * skipped recovery sweep.
     */
    private fun sweepLocalLlmNativeCrashes() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return  // ApplicationExitInfo is API 30+
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val am = getSystemService(android.app.ActivityManager::class.java) ?: return@runCatching
                // Look at the last ~5 exits: more than enough to spot a recent crash even if
                // the user opened the app a few times since (each open = one exit record).
                val recentExits = am.getHistoricalProcessExitReasons(packageName, 0, 5)
                val nativeCrash = recentExits.firstOrNull { exit ->
                    exit.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE &&
                        // ApplicationExitInfo.description includes the offending shared library
                        // for native crashes. Match the JNI sidekick of each runtime.
                        (exit.description?.contains("liblitertlm", ignoreCase = true) == true)
                } ?: return@runCatching
                val prefs = get<me.rerere.locallm.LocalRuntimePreferences>()
                val runtime = me.rerere.locallm.LocalRuntime.LiteRT
                // Don't double-stamp if the user has already seen and dismissed an earlier
                // crash banner — the prior dismiss cleared the recovery key, but if a NEW
                // crash happened after, we want a fresh notice.
                val crashedAccel = prefs.acceleratorFlow(runtime).first() ?: "GPU/NPU"
                if (!prefs.forceCpu(runtime)) {
                    prefs.setForceCpu(runtime, true)
                    prefs.clearAccelerator(runtime)
                }
                prefs.setCrashRecovery(runtime, crashedAccel)
                Log.w(
                    TAG,
                    "sweepLocalLlmNativeCrashes: detected native crash in liblitertlm at " +
                        "${nativeCrash.timestamp} (accel=$crashedAccel) — forcing CPU + stamping recovery banner"
                )
            }.onFailure {
                Log.w(TAG, "sweepLocalLlmNativeCrashes failed", it)
            }
        }
    }

    /**
     * Fire-and-forget: clear stale SDK-coupled decisions (accelerator, vision-unavailable
     * set, crash-recovery banner) whenever the compiled-in LiteRT-LM version differs from
     * the last-persisted one. Best-effort — failure is logged and ignored so a slow or
     * broken DataStore read can never block app start. Idempotent across multiple calls
     * within the same process (the version write makes the second call a no-op).
     */
    private fun invalidateLocalLlmDecisionsOnSdkUpgrade() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val prefs = get<me.rerere.locallm.LocalRuntimePreferences>()
                val invalidated = prefs.maybeInvalidateOnSdkUpgrade(me.rerere.locallm.LocalRuntime.LiteRT)
                if (invalidated) {
                    Log.i(
                        TAG,
                        "invalidateLocalLlmDecisionsOnSdkUpgrade: SDK version changed — cleared " +
                            "accelerator + vision-unavailable + crash-recovery for LiteRT (new=${prefs.currentSdkVersion})",
                    )
                }
                // Unconditionally wipe the visionUnavailable set on every app start. Stale
                // flags can be left behind by transient failures the SDK has since
                // recovered from (most notably the 0.12.0 -> 0.11.0 downgrade where the
                // SDK-version key may already match because the device ran 0.11.0 first).
                // If GPU vision really is broken on this device, [LiteRtRuntime.ensureLoaded]
                // re-stamps the flag the moment it observes a fresh failure — so the wipe
                // never strands the app in a crash loop, it just ensures we re-test on
                // every launch.
                val wipedVision = prefs.clearAllVisionUnavailable(me.rerere.locallm.LocalRuntime.LiteRT)
                if (wipedVision > 0) {
                    Log.i(
                        TAG,
                        "invalidateLocalLlmDecisionsOnSdkUpgrade: wiped $wipedVision stale " +
                            "visionUnavailable entries (forcing fresh attempt next inference)",
                    )
                }
            }.onFailure {
                Log.w(TAG, "invalidateLocalLlmDecisionsOnSdkUpgrade failed", it)
            }
        }
    }

    /**
     * Phase 24 — run the unified AgentRun ledger boot-recovery sweep once per process
     * start. Best-effort: a slow or failed sweep must never block app start, so it runs on
     * the IO dispatcher off [AppScope] and swallows its own failures.
     */
    private fun runAgentRunBootRecovery() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<me.rerere.rikkahub.data.agentrun.AgentRunBootRecovery>().runRecovery()
            }.onFailure {
                Log.w(TAG, "runAgentRunBootRecovery failed", it)
            }
        }
    }

    private fun startWorkflowRegistry() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val registry = get<me.rerere.rikkahub.workflow.trigger.TriggerRegistry>()
                val engine = get<me.rerere.rikkahub.workflow.execution.WorkflowEngine>()
                registry.setEngineCallback(engine.triggerCallback)
                registry.start()
            }.onFailure {
                Log.e(TAG, "startWorkflowRegistry failed", it)
            }
        }
    }

    private fun startNetworkChangeMonitor() {
        runCatching {
            val client = get<okhttp3.OkHttpClient>()
            me.rerere.rikkahub.utils.NetworkChangeMonitor.start(this, client)
        }.onFailure {
            Log.w(TAG, "startNetworkChangeMonitor failed", it)
        }
    }

    /**
     * Cleans up orphan conversations left by cron workers that were killed mid-execute.
     *
     * When a worker is killed between HeadlessConversations.mark() and unmark(), the
     * conversation ID remains in SharedPreferences. On the next app start we detect these
     * IDs and delete the corresponding "[Scheduled]" conversations from the DB so they
     * don't pollute the chat list.
     *
     * We clear the persisted set at the end regardless — if a conversation doesn't exist in
     * DB there's nothing to clean up, and stale IDs only confuse future sweeps.
     */
    private fun sweepOrphanHeadlessConversations() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val orphanIds = HeadlessConversations.activeIds()
                if (orphanIds.isEmpty()) return@runCatching
                Log.i(TAG, "sweepOrphanHeadlessConversations: found ${orphanIds.size} candidate(s)")
                val convRepo = get<me.rerere.rikkahub.data.repository.ConversationRepository>()
                for (id in orphanIds) {
                    runCatching {
                        val conv = convRepo.getConversationById(id)
                        if (conv != null && conv.title.startsWith("[Scheduled]")) {
                            Log.i(TAG, "sweepOrphanHeadlessConversations: deleting orphan conv $id")
                            convRepo.deleteConversation(conv)
                        }
                    }.onFailure { Log.w(TAG, "sweepOrphanHeadlessConversations: error for $id", it) }
                }
                HeadlessConversations.clearAll()
                Log.i(TAG, "sweepOrphanHeadlessConversations: sweep complete")
            }.onFailure {
                Log.e(TAG, "sweepOrphanHeadlessConversations failed", it)
            }
        }
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch {
            runCatching {
                val store = get<SettingsStore>()
                val count = store.incrementLaunchCount()
                Log.i(TAG, "incrementLaunchCount: $count")
            }.onFailure {
                Log.e(TAG, "incrementLaunchCount failed", it)
            }
        }
    }

    private fun startTelegramBotIfEnabled() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val cfg = get<me.rerere.rikkahub.data.telegram.TelegramBotPreferences>().current()
                if (cfg.isUsable) {
                    Log.i(TAG, "startTelegramBotIfEnabled: re-starting bot service")
                    me.rerere.rikkahub.service.TelegramBotService.start(this@RikkaHubApp)
                    // Defense-in-depth against OEM aggressive task-killing: a 30-min
                    // periodic health probe re-starts the service if anything killed it
                    // outside our control. Idempotent — uses ExistingPeriodicWorkPolicy.KEEP.
                    me.rerere.rikkahub.service.TelegramBotHealthWorker.schedule(this@RikkaHubApp)
                } else {
                    me.rerere.rikkahub.service.TelegramBotHealthWorker.cancel(this@RikkaHubApp)
                }
            }.onFailure {
                Log.e(TAG, "startTelegramBotIfEnabled failed", it)
            }
        }
    }

    private fun scheduleDeferredMaintenance() {
        get<AppScope>().launch(Dispatchers.IO) {
            // Give the main thread 2.5 seconds to complete first frame composition and display
            delay(2500)
            runCatching {
                val dir = appTempFolder
                if (dir.exists()) dir.deleteRecursively()
            }
            runCatching {
                val dir = File(filesDir, FileFolders.TOOL_OUTPUTS)
                if (dir.exists()) dir.deleteRecursively()
            }
            runCatching {
                get<WorkspaceManager>().cleanupAllTempDirs()
            }
            runCatching {
                get<WorkspaceRepository>().checkIntegrity()
            }
            runCatching {
                get<FilesManager>().syncFolder()
            }
            runCatching {
                get<SkillManager>().ensureBuiltinSkillsExtracted()
            }
            runCatching {
                get<SkillManager>().seedDefaultSkillsIfNeeded()
            }
            sweepLocalLlmNativeCrashes()
            invalidateLocalLlmDecisionsOnSdkUpgrade()
        }
    }

    private fun updateModelsDev() {
        get<AppScope>().launch {
            get<ModelsDevUpdater>().start()
        }
    }

    private fun startWebServerIfEnabled() {
        get<AppScope>().launch {
            runCatching {
                delay(500)
                val settings = get<SettingsStore>().settingsFlowRaw.first()
                if (settings.webServerEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: notification permission not granted, skipping")
                        return@launch
                    }
                    // Android 17 (API 37) requires ACCESS_LOCAL_NETWORK to bind to LAN
                    // interfaces. localhost-only mode does not need it because traffic stays
                    // within the app's UID. Cherry-picked from upstream 80186f5d.
                    if (Build.VERSION.SDK_INT >= 37 &&
                        !settings.webServerLocalhostOnly &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.ACCESS_LOCAL_NETWORK
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: local network permission not granted, skipping")
                        return@launch
                    }
                    val intent = Intent(this@RikkaHubApp, WebServerService::class.java).apply {
                        action = WebServerService.ACTION_START
                        putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
                        putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
                    }
                    startForegroundService(intent)
                }
            }.onFailure {
                Log.e(TAG, "startWebServerIfEnabled failed", it)
            }
        }
    }

    private fun resumeMediaCreations() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<MediaCreationService>().resumePending()
            }.onFailure {
                Log.e(TAG, "resumeMediaCreations failed", it)
            }
        }
    }

    private fun resumeMemoryConsolidation() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        runCatching { get<me.rerere.locallm.litert.LiteRtRuntime>().isAppForeground = true }
                    }
                    Lifecycle.Event.ON_STOP -> {
                        runCatching { get<me.rerere.locallm.litert.LiteRtRuntime>().isAppForeground = false }
                        // Never trigger automatic background memory consolidation when the
                        // configured consolidation/fast/chat model is an on-device local LLM:
                        // loading a 2-4 GB LiteRT / GGUF model the instant the user presses
                        // Home starves Android Launcher and SystemUI of RAM and CPU.
                        val settings = runCatching { get<SettingsStore>().settingsFlow.value }.getOrNull()
                        val isLocalOnDevice = settings != null && (
                            listOfNotNull(
                                settings.consolidationModel()?.first?.findProvider(settings.providers),
                                settings.findModelById(settings.fastModelId)?.findProvider(settings.providers),
                            ).any { it is ProviderSetting.LiteRtLocal || it is ProviderSetting.LlamaCppLocal } ||
                            settings.assistants.filter { it.enableMemory }.any { assistant ->
                                val provider = settings.consolidationModel(assistant = assistant)?.first?.findProvider(settings.providers)
                                provider is ProviderSetting.LiteRtLocal || provider is ProviderSetting.LlamaCppLocal
                            }
                        )
                        if (!isLocalOnDevice) {
                            get<MemoryConsolidationScheduler>().onAppHidden()
                        }
                    }
                    else -> {}
                }
            }
        )
        get<AppScope>().launch(Dispatchers.IO) {
            // Defer pending memory scan until after UI startup settles
            delay(4000)
            runCatching {
                get<MemoryConsolidationScheduler>().resumePending()
            }.onFailure {
                Log.e(TAG, "resumeMemoryConsolidation failed", it)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)

        val webServerChannel = NotificationChannelCompat
            .Builder(WEB_SERVER_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_web_server))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(webServerChannel)

        val mediaCreationChannel = NotificationChannelCompat
            .Builder(MEDIA_CREATION_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(getString(R.string.media_creation_title))
            .build()
        notificationManager.createNotificationChannel(mediaCreationChannel)

        val memoryDreamChannel = NotificationChannelCompat
            .Builder(MEMORY_DREAM_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.memory_dream_title))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(memoryDreamChannel)
    }

    override fun onTerminate() {
        super.onTerminate()
        get<AppScope>().cancel()
        stopService(Intent(this, WebServerService::class.java))
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob() +
        Dispatchers.Main +
        CoroutineName("AppScope") +
        CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "AppScope exception", e)
        }
)
