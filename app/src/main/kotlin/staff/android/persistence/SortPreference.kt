package staff.android.persistence

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

// One DataStore instance for this file in this application process.
private val Context.catalogPreferences by preferencesDataStore("catalog_preferences")
enum class SavedSort { Latest, Title }

class SortPreference(context: Context) {
    private val store = context.applicationContext.catalogPreferences
    private val key = stringPreferencesKey("saved_sort")
    val sort = store.data.map { values ->
        SavedSort.entries.firstOrNull { it.name == values[key] } ?: SavedSort.Latest
    }
    suspend fun set(value: SavedSort) { store.edit { it[key] = value.name } }
}
