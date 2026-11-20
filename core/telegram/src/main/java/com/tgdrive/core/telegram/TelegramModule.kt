package com.tgdrive.core.telegram

import android.content.Context
import com.tgdrive.core.common.DefaultDispatchersProvider
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.NetworkMonitor
import com.tgdrive.core.security.KeystoreHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TelegramModule {

    @Provides
    @Singleton
    fun provideTelegramClient(
        dispatchers: DispatchersProvider,
        @ApplicationContext ctx: Context,
        keystoreHelper: KeystoreHelper,
    ): TelegramClient = TdLibTelegramClient(dispatchers, ctx, keystoreHelper)

    @Provides
    @Singleton
    fun provideDispatchers(): DispatchersProvider = DefaultDispatchersProvider()

    @Provides
    @Singleton
    fun provideNetworkMonitor(@ApplicationContext ctx: Context): NetworkMonitor =
        NetworkMonitor(ctx)
}
