package com.rendyhd.vicu.di

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.rendyhd.vicu.data.local.VikunjaDatabase
import com.rendyhd.vicu.data.local.VikunjaDatabaseConstructor
import com.rendyhd.vicu.ui.screens.customlist.CustomListViewModel
import com.rendyhd.vicu.ui.screens.guided.GuidedSortViewModel
import com.rendyhd.vicu.ui.screens.pro.PersonViewModel
import com.rendyhd.vicu.ui.screens.project.ProjectViewModel
import com.rendyhd.vicu.ui.screens.tag.TagViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.check.checkKoinModules
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The iOS side of KoinGraphTest: every single, factory and view model of the modules the iOS app
 * starts with ([sharedModules] and [iosModule]) is built through Koin, so a missing binding fails
 * here instead of at launch. The real iOS implementations are built (Keychain storage, the network
 * monitor, the notification and file platforms); only the database is replaced by one in memory.
 * The main dispatcher is a test dispatcher that never runs, so nothing does work beyond being built.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IosKoinGraphTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    @Test
    @Suppress("DEPRECATION") // As in KoinGraphTest: verify() cannot read lambda definitions.
    fun everyDefinitionResolves() {
        checkKoinModules(
            modules = sharedModules + iosModule + testEdges(),
            parameters = {
                withParameter<CustomListViewModel> { SavedStateHandle(mapOf("listId" to "list-1")) }
                withParameter<ProjectViewModel> { SavedStateHandle(mapOf("projectId" to 1L)) }
                withParameter<TagViewModel> { SavedStateHandle(mapOf("labelId" to 1L)) }
                withParameter<GuidedSortViewModel> { SavedStateHandle(mapOf("kind" to "inbox")) }
                withParameter<PersonViewModel> { SavedStateHandle(mapOf("labelId" to 1L)) }
            },
        )
    }

    private fun testEdges(): Module = module {
        single<VikunjaDatabase> {
            Room.inMemoryDatabaseBuilder<VikunjaDatabase>(factory = { VikunjaDatabaseConstructor.initialize() })
                .setDriver(BundledSQLiteDriver())
                .build()
        }
    }
}
