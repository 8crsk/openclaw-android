package com.crsk.openclaw.data.network.ws

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WsModule {

    @Binds
    @Singleton
    abstract fun bindWsEventSource(impl: WsRpcClient): WsEventSource

    @Binds
    @Singleton
    abstract fun bindWsRpcCaller(impl: WsRpcClient): WsRpcCaller
}