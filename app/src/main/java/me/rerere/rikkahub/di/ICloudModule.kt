package me.rerere.rikkahub.di

import app.amber.feature.icloud.ICloudDriveClient
import app.amber.feature.icloud.ICloudDriveCookieProvider
import app.amber.feature.icloud.ICloudDriveManager
import app.amber.feature.tools.ICloudDriveTools
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
