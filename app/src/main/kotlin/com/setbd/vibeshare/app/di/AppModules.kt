package com.setbd.vibeshare.app.di

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.room.Room
import com.setbd.vibeshare.apps.AndroidAppsRepository
import com.setbd.vibeshare.core.coroutines.DefaultDispatcherProvider
import com.setbd.vibeshare.core.coroutines.DispatcherProvider
import com.setbd.vibeshare.data.db.VibeDatabase
import com.setbd.vibeshare.data.prefs.DataStoreSettingsRepository
import com.setbd.vibeshare.data.repository.RoomHistoryRepository
import com.setbd.vibeshare.discovery.AndroidDiscoveryRepository
import com.setbd.vibeshare.discovery.auto.AutoTransportSelector
import com.setbd.vibeshare.domain.model.DeviceInfo
import com.setbd.vibeshare.domain.model.DeviceType
import com.setbd.vibeshare.domain.model.DuplicatePolicy
import com.setbd.vibeshare.domain.repository.AppsRepository
import com.setbd.vibeshare.domain.repository.DiscoveryRepository
import com.setbd.vibeshare.domain.repository.HistoryRepository
import com.setbd.vibeshare.domain.repository.SettingsRepository
import com.setbd.vibeshare.domain.repository.StorageRepository
import com.setbd.vibeshare.pairing.handshake.PendingApprovalBus
import com.setbd.vibeshare.pairing.session.SessionRegistry
import com.setbd.vibeshare.storage.AndroidIncomingStorage
import com.setbd.vibeshare.storage.VibeStorageRepository
import com.setbd.vibeshare.app.transfer.TransferCoordinator
import com.setbd.vibeshare.app.ui.screens.AppsViewModel
import com.setbd.vibeshare.app.ui.screens.BuildVersion
import com.setbd.vibeshare.app.ui.screens.DevModeSink
import com.setbd.vibeshare.app.ui.screens.DevViewModel
import com.setbd.vibeshare.app.ui.screens.HistoryViewModel
import com.setbd.vibeshare.app.ui.screens.HomeViewModel
import com.setbd.vibeshare.app.ui.screens.ReceiveViewModel
import com.setbd.vibeshare.app.ui.screens.SendViewModel
import com.setbd.vibeshare.app.ui.screens.SettingsViewModel
import com.setbd.vibeshare.app.updater.UpdateManager
import com.setbd.vibeshare.domain.usecase.ManageHistoryUseCase
import com.setbd.vibeshare.transfer.model.IncomingStorage
import kotlinx.coroutines.flow.first
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/** Single Koin graph for the whole application. */
fun initKoin(): List<Module> = listOf(platformModules)

private val platformModules = module {
    single<DispatcherProvider> { DefaultDispatcherProvider() }

    // NOTE: the Android Context is auto-registered by koin-android when
    // startKoin { androidContext(...) } runs. Do NOT re-declare
    // `single<Context> { androidContext() }` — in koin-android 4.x
    // androidContext() resolves get<Context>(), which recursed infinitely.

    single<SettingsRepository> { DataStoreSettingsRepository(androidContext()) }

    single<HistoryRepository> {
        RoomHistoryRepository(
            Room.databaseBuilder(androidContext(), VibeDatabase::class.java, "vibeshare.db")
                .fallbackToDestructiveMigration()
                .build()
                .historyDao()
        )
    }

    single<VibeStorageRepository> { VibeStorageRepository(androidContext()) }
    single<StorageRepository> { get<VibeStorageRepository>() }

    single<IncomingStorage> {
        val context = androidContext()
        val storageRepo = get<VibeStorageRepository>()
        val settings = get<SettingsRepository>()
        AndroidIncomingStorage(
            context = context,
            repository = storageRepo,
            duplicatePolicyProvider = { settings.settings.first().defaultDuplicatePolicy },
        )
    }

    single<AppsRepository> { AndroidAppsRepository(androidContext()) }

    single<AutoTransportSelector> { AutoTransportSelector(androidContext()) }

    // Bound to both the concrete type (used by screens) and the domain interface.
    single { AndroidDiscoveryRepository(androidContext(), get<AutoTransportSelector>()) }
        .bind(DiscoveryRepository::class)

    single<SessionRegistry> { SessionRegistry() }
    single<PendingApprovalBus> { PendingApprovalBus() }

    single { UpdateManager(androidContext(), BuildVersion.CODE) }
    single { DevModeSink() }
    factory { ManageHistoryUseCase(get()) }

    single<DeviceInfo> {
        val context = androidContext()
        DeviceInfo(
            deviceId = stableDeviceId(context),
            name = context.filesDir.let { Build.MODEL }, // replaced per-read by coordinator
            type = DeviceType.PHONE,
        )
    }

    single<TransferCoordinator> {
        val context = androidContext()
        val settings = get<SettingsRepository>()
        val storageRepo = get<VibeStorageRepository>()
        val base = get<DeviceInfo>()
        TransferCoordinator(
            registry = get(),
            approvalBus = get(),
            incomingStorage = get(),
            discovery = get() as AndroidDiscoveryRepository,
            settingsRepository = settings,
            historyRepository = get(),
            dispatchers = get(),
            selfIdentity = {
                val name = kotlinx.coroutines.runBlocking {
                    settings.settings.first().deviceName.ifBlank { Build.MODEL }
                }
                base.copy(name = name)
            },
        )
    }

    // ---- ViewModels (resolved via koinViewModel() in Compose screens) ----
    viewModel { HomeViewModel(get(), get(), get()) }
    viewModel { SendViewModel(get(), get(), get(), get()) }
    viewModel { ReceiveViewModel(get(), get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get(), get()) }
    viewModel { HistoryViewModel(get()) }
    viewModel { AppsViewModel(get()) }
    viewModel { DevViewModel(get(), get(), get(), get()) }
}

/** Stable, privacy-safe device identifier: a random install-scoped UUID. */
fun stableDeviceId(context: Context): String {
    val prefs = context.getSharedPreferences("vibeshare_device", Context.MODE_PRIVATE)
    prefs.getString("device_id", null)?.let { return it }
    val id = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
    prefs.edit().putString("device_id", id).apply()
    return id
}
