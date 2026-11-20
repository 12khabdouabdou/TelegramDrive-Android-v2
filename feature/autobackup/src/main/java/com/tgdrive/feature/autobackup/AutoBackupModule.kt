package com.tgdrive.feature.autobackup

import com.tgdrive.feature.autobackup.diff.DiffEngine
import com.tgdrive.feature.autobackup.diff.Sha256Calculator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for the autobackup package.
 *
 * Most classes ([FolderScanner], [FolderWatcher], [StagingManager],
 * [BackupOrchestrator], [ActiveBackupRegistry], [Sha256Calculator]) have
 * `@Inject constructor` + `@Singleton` annotations directly on the class,
 * so Hilt auto-binds them. This module exists for [DiffEngine], which
 * takes a function-typed dependency and needs manual binding.
 */
@Module
@InstallIn(SingletonComponent::class)
object AutoBackupModule {

    @Provides
    @Singleton
    fun provideDiffEngine(sha: Sha256Calculator): DiffEngine =
        DiffEngine { uri -> sha.compute(uri) }
}
