package com.veeha.fastfin

import com.veeha.fastfin.data.LibraryQuery
import com.veeha.fastfin.data.LibrarySort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library sort and filter chips, as Jellyfin query parameters. */
class LibraryQueryTest {

    @Test
    fun noFiltersSendsNoFiltersParameter() {
        val query = LibraryQuery()
        assertNull(query.filters)
        assertFalse(query.filtered)
    }

    @Test
    fun toggledChipsBecomeJellyfinFilters() {
        val query = LibraryQuery(unwatched = true, inProgress = true, favorites = true)
        assertEquals("IsUnplayed,IsResumable,IsFavorite", query.filters)
        assertTrue(query.filtered)
    }

    @Test
    fun genreAloneCountsAsFiltered() {
        assertTrue(LibraryQuery(genre = "Action & Adventure").filtered)
        assertNull(LibraryQuery(genre = "Comedy").filters)
    }

    @Test
    fun airingAsksForContinuingShows() {
        assertEquals("Continuing", LibraryQuery(airing = true).seriesStatus)
        assertNull(LibraryQuery().seriesStatus)
        assertTrue(LibraryQuery(airing = true).filtered)
    }

    @Test
    fun everySortBreaksTiesByName() {
        for (sort in LibrarySort.entries) assertTrue(sort.sortBy.endsWith("SortName"))
    }

    @Test
    fun differentQueriesNeverShareACachedList() {
        val base = LibraryQuery(sort = LibrarySort.Added, descending = true)
        assertNotEquals(base.key, base.copy(descending = false).key)
        assertNotEquals(base.key, base.copy(genre = "Drama").key)
        assertNotEquals(base.key, base.copy(unwatched = true).key)
        assertNotEquals(base.key, base.copy(airing = true).key)
        assertEquals(base.key, base.copy().key)
    }
}
