package com.rendyhd.vicu.di

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.lifecycle.SavedStateHandle
import androidx.work.WorkerParameters
import com.rendyhd.vicu.data.local.VikunjaDatabase
import com.rendyhd.vicu.data.local.VikunjaDatabase_Impl
import com.rendyhd.vicu.data.local.dao.AttachmentDao
import com.rendyhd.vicu.data.local.dao.LabelDao
import com.rendyhd.vicu.data.local.dao.LocalDataDao
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.ProjectDao
import com.rendyhd.vicu.data.local.dao.RoutineArchiveDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.ui.screens.customlist.CustomListViewModel
import com.rendyhd.vicu.ui.screens.project.ProjectViewModel
import com.rendyhd.vicu.ui.screens.tag.TagViewModel
import com.rendyhd.vicu.util.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.test.check.checkKoinModules
import java.io.File
import java.lang.reflect.Proxy
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/**
 * The Koin wiring is lambda-based and positional, so a missing or wrong binding only shows up
 * the first time the app builds the class that needs it. These tests take the same module lists
 * the app starts with ([sharedModules] and [androidAppModules]) and check them without a device.
 * Koin's static verify() is not used: it cannot read a lambda definition, and it fails on the
 *
 *
 * - [every definition resolves] instantiates every single, factory and ViewModel through Koin
 *   (checkModules). Only the edges that need a running Android are replaced (see [platformEdges]),
 *   and the main dispatcher is a test dispatcher that never runs launched coroutines, so no class
 *   does any work beyond being constructed.
 * - [every worker constructor resolves] covers the WorkManager workers, which cannot be built
 *   outside WorkManager (they need its WorkerParameters), by resolving each constructor argument
 *   other than Context and WorkerParameters.
 */
@OptIn(ExperimentalCoroutinesApi::class, KoinExperimentalAPI::class, KoinInternalApi::class)
class KoinGraphTest {

    private val allModules: List<Module> = sharedModules + androidAppModules

    /** Workers cannot be instantiated outside WorkManager, so they get their own check below. */
    private val nonWorkerModules: List<Module> = allModules.filter { it !== workerModule }

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    @Test
    @Suppress("DEPRECATION") // Koin deprecates checkModules for verify(), which cannot read these lambdas.
    fun `every definition resolves`() {
        checkKoinModules(
            modules = nonWorkerModules + platformEdges(),
            appDeclaration = { androidContext(emptyContext()) },
            parameters = {
                // The route arguments the navigation graph passes when it creates these.
                withParameter<CustomListViewModel> { SavedStateHandle(mapOf("listId" to "list-1")) }
                withParameter<ProjectViewModel> { SavedStateHandle(mapOf("projectId" to 1L)) }
                withParameter<TagViewModel> { SavedStateHandle(mapOf("labelId" to 1L)) }
            },
        )
    }

    @Test
    fun `every worker constructor resolves`() {
        val koin = koinApplication {
            androidContext(emptyContext())
            modules(nonWorkerModules + platformEdges())
        }.koin

        val workerClasses = workerModule.mappings.values
            .map { it.beanDefinition.primaryType }
            .distinct()
        assertTrue("workerModule declares no workers", workerClasses.isNotEmpty())

        for (worker in workerClasses) {
            val constructor = checkNotNull(worker.primaryConstructor) { "${worker.simpleName} has no constructor" }
            for (parameter in constructor.parameters) {
                val type = parameter.type.classifier as KClass<*>
                if (type == Context::class || type == WorkerParameters::class) continue
                // Throws NoDefinitionFoundException naming the missing type.
                koin.get<Any>(type)
            }
        }
    }

    /** A Context that is never asked for anything: the unit-test android.jar returns defaults. */
    private fun emptyContext(): Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo()
        override fun getCacheDir(): File = File(System.getProperty("java.io.tmpdir"), "vicu-koin-graph-test")
    }

    /** The definitions that need a running Android, replaced for the whole graph. */
    private fun platformEdges(): Module = module {
        // Room opens the database on first use; the generated class is enough to hand out as a
        // singleton, but its DAOs build their flows from the open database, so they are stand-ins
        // that return empty flows (a repository starts observing one as it is constructed).
        single<VikunjaDatabase> { VikunjaDatabase_Impl() }
        single<TaskDao> { emptyDao() }
        single<ProjectDao> { emptyDao() }
        single<LabelDao> { emptyDao() }
        single<PendingActionDao> { emptyDao() }
        single<AttachmentDao> { emptyDao() }
        single<RoutineArchiveDao> { emptyDao() }
        single<LocalDataDao> { emptyDao() }
        // The real monitor registers a ConnectivityManager callback as it is built.
        single<NetworkMonitor> {
            object : NetworkMonitor {
                override val isOnline: StateFlow<Boolean> = MutableStateFlow(true)
            }
        }
    }

    /** A DAO whose flows are empty and whose other methods return nothing. */
    private inline fun <reified T : Any> emptyDao(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            when (method.returnType) {
                Flow::class.java -> emptyFlow<Any?>()
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as T
}
