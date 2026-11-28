// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.rune.mt.forum.data.settings.local.datastore.AppDataStore
import io.rune.mt.forum.data.settings.repository.AppSettingsRepositoryImpl
import io.rune.mt.forum.domain.mcp.McpEndpoint
import io.rune.mt.forum.domain.settings.repository.BooleanSetting
import io.rune.mt.forum.domain.settings.repository.IntSetting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Persistence of 设置 → MCP 服务 through the **existing** app-settings DataStore.
 *
 * The backing `DataStore<Preferences>` is an in-memory double (same rationale as
 * `ForumSearchHistoryStoreTest`: a real file-backed DataStore cannot replace its file on Windows).
 * What is under test is our own mapping: the switch and port round-trip through
 * `AppDataStore`/`AppSettingsRepositoryImpl`, including the "app restarted" case and the
 * validation that keeps a privileged/invalid port from ever being bound.
 */
class McpSettingsStoreTest {

    private lateinit var backing: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private lateinit var repository: AppSettingsRepositoryImpl

    @Before
    fun setUp() {
        backing = McpSettingsMemoryDataStore()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        repository = AppSettingsRepositoryImpl(AppDataStore(backing), scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `the server is off by default and listens on the documented port`() = runBlocking {
        val preferences = repository.preferencesFlow.first { true }

        assertFalse(preferences.mcpServerEnabled)
        assertEquals(McpEndpoint.DEFAULT_PORT, preferences.mcpServerPort)
    }

    @Test
    fun `switch and port survive a repository restart`() = runBlocking {
        repository.putBoolean(BooleanSetting.McpServerEnabled, true)
        repository.putInt(IntSetting.McpServerPort, 9123)

        val beforeRestart = repository.preferencesFlow.first { it.mcpServerEnabled }
        assertTrue(beforeRestart.mcpServerEnabled)
        assertEquals(9123, beforeRestart.mcpServerPort)

        // Same backing store, brand-new repository: this is the process-restart path.
        val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val restarted = AppSettingsRepositoryImpl(AppDataStore(backing), restartScope)
            val afterRestart = restarted.preferencesFlow.first { it.mcpServerEnabled }
            assertTrue(afterRestart.mcpServerEnabled)
            assertEquals(9123, afterRestart.mcpServerPort)
        } finally {
            restartScope.cancel()
        }
    }

    @Test
    fun `disabling the server persists false`() = runBlocking {
        repository.putBoolean(BooleanSetting.McpServerEnabled, true)
        repository.putBoolean(BooleanSetting.McpServerEnabled, false)

        assertFalse(repository.preferencesFlow.first { true }.mcpServerEnabled)
    }

    @Test
    fun `a privileged port is normalized to the default instead of reaching bind`() = runBlocking {
        repository.putInt(IntSetting.McpServerPort, 80)

        assertEquals(McpEndpoint.DEFAULT_PORT, repository.preferencesFlow.first { true }.mcpServerPort)
    }

    @Test
    fun `an out-of-range port is normalized to the default`() = runBlocking {
        repository.putInt(IntSetting.McpServerPort, 70_000)

        assertEquals(McpEndpoint.DEFAULT_PORT, repository.preferencesFlow.first { true }.mcpServerPort)
    }

    @Test
    fun `the switch and port use the existing app settings datastore keys`() = runBlocking {
        repository.putBoolean(BooleanSetting.McpServerEnabled, true)
        repository.putInt(IntSetting.McpServerPort, 8766)

        val stored = backing.data.first()
        assertEquals(true, stored[AppDataStore.MCP_SERVER_ENABLED])
        assertEquals(8766, stored[AppDataStore.MCP_SERVER_PORT])
    }
}

/** In-memory [DataStore] of [Preferences]; see the class KDoc for why the real one is unusable. */
private class McpSettingsMemoryDataStore : DataStore<Preferences> {

    private val state = MutableStateFlow<Preferences>(emptyPreferences())

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}