// SPDX-License-Identifier: GPL-3.0-only
package io.rune.mt.forum.ui.page.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * State of 设置 → 插件 → 帖子缓存.
 *
 * [count] / [bytes] are read from the cache's own index, so the readout is the same document the
 * eviction uses — a number that disagrees with the directory is not possible.
 */
data class ForumThreadCacheState(
    val isLoading: Boolean = true,
    val enabled: Boolean = true,
    val count: Int = 0,
    val bytes: Long = 0L,
    val error: String? = null,
)

/**
 * Backs the 帖子缓存 page: the switch, the usage readout and 清空.
 *
 * The cache is device-local ([ForumThreadCacheRepository]) and has no login gate — it is the
 * device's own copy, like 浏览历史. Every call is wrapped: a broken directory must show as an
 * unavailable card, never crash the settings page.
 */
class ForumThreadCacheViewModel(
    private val cache: ForumThreadCacheRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ForumThreadCacheState())
    val state: StateFlow<ForumThreadCacheState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = it.count == 0, error = null) }
            try {
                val stats = cache.stats()
                _state.value = ForumThreadCacheState(
                    isLoading = false,
                    enabled = stats.enabled,
                    count = stats.count,
                    bytes = stats.bytes,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "thread cache stats failed")
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        _state.update { it.copy(enabled = enabled) }
        viewModelScope.launch {
            try {
                cache.setEnabled(enabled)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "thread cache switch failed")
                _state.update {
                    it.copy(
                        enabled = !enabled,
                        error = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }
    }

    /** Drops every cached thread (the page asks for confirmation first). */
    fun clear() {
        viewModelScope.launch {
            try {
                cache.clear()
                refresh()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "thread cache clear failed")
                _state.update {
                    it.copy(error = error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    private companion object {
        const val TAG = "ThreadCacheVM"
    }
}
