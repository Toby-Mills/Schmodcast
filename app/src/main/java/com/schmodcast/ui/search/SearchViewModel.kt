package com.schmodcast.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schmodcast.data.model.Podcast
import com.schmodcast.data.remote.NetworkModule
import com.schmodcast.data.remote.toDomainOrNull
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Success(val results: List<Podcast>) : SearchUiState
    data class Error(val message: String) : SearchUiState
}

private const val SEARCH_DEBOUNCE_MS = 400L

class SearchViewModel : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
        searchJob?.cancel()

        if (newQuery.isBlank()) {
            _uiState.value = SearchUiState.Idle
            return
        }

        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _uiState.value = SearchUiState.Loading
            _uiState.value = runSearch(newQuery)
        }
    }

    private suspend fun runSearch(term: String): SearchUiState = try {
        val response = NetworkModule.itunesApi.searchPodcasts(term = term)
        SearchUiState.Success(response.results.mapNotNull { it.toDomainOrNull() })
    } catch (e: IOException) {
        SearchUiState.Error("Couldn't reach the podcast directory. Check your connection.")
    } catch (e: HttpException) {
        SearchUiState.Error("Search failed (HTTP ${e.code()}).")
    }
}
