package me.rerere.rikkahub.di

import me.rerere.rikkahub.data.agent.icloud.ICloudDriveClient
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveCookieProvider
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveManager
import me.rerere.rikkahub.data.agent.tools.ICloudDriveTools
import org.koin.dsl.module

/**
 * iCloud Drive integration Koin module — cookie provider, HTTP client,
 * manager, and tool surface. Extracted from AppModule in M1.5 continuation.
 */
val iCloudModule = module {
    single { ICloudDriveCookieProvider() }

    single { ICloudDriveClient(get(), get()) }

    single { ICloudDriveManager(get(), get(), get()) }

    single { ICloudDriveTools(get(), get()) }
}
