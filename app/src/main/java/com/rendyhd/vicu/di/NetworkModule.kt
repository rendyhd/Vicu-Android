package com.rendyhd.vicu.di

import android.content.Context
import android.content.pm.ApplicationInfo
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.remote.interceptor.AuthInterceptor
import com.rendyhd.vicu.data.remote.interceptor.BaseUrlHolder
import com.rendyhd.vicu.data.remote.interceptor.BaseUrlInterceptor
import com.rendyhd.vicu.data.remote.interceptor.TokenAuthenticator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideBaseUrlHolder(): BaseUrlHolder = BaseUrlHolder()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideBaseUrlInterceptor(holder: BaseUrlHolder): BaseUrlInterceptor =
        BaseUrlInterceptor(holder)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        baseUrlInterceptor: BaseUrlInterceptor,
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator,
        @ApplicationContext context: Context,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(baseUrlInterceptor)
            .addInterceptor(authInterceptor)
            .authenticator(tokenAuthenticator)

        val isDebug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (isDebug) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                }
            )
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl("http://localhost/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): VikunjaApiService =
        retrofit.create(VikunjaApiService::class.java)
}
