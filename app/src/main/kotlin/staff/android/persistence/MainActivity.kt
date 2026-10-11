package staff.android.persistence

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ShopScreen(
    val products: List<ProductEntity> = emptyList(),
    val saved: List<SavedRow> = emptyList(),
    val sort: SavedSort = SavedSort.Latest,
    val problem: String? = null,
    val loading: Boolean = true,
    val available: Boolean = false,
    val writing: Boolean = false,
)

class ShopOwner(
    app: Application, policy: OpenPolicy
) : AndroidViewModel(app) {
    private val database = openShop(app, policy = policy)
    private val store = ShopStore(database)
    private val preference = SortPreference(app)
    val screen = MutableStateFlow(ShopScreen())
    private var observation: Job? = null
    private val writes = Mutex()
    init { reload() }

    fun reload() {
        observation?.cancel()
        screen.value = screen.value.copy(
            loading = true, problem = null
        )
        observation = viewModelScope.launch {
            try {
                combine(
                    store.products, store.saved, preference.sort
                ) { products, saved, sort ->
                    // Title orders the selected recent-50 window.
                    val ordered = if (sort == SavedSort.Title)
                        saved.sortedWith(
                            compareBy<SavedRow> { it.title }
                                .thenBy { it.id }
                        ) else saved
                    ShopScreen(
                        products, ordered, sort,
                        loading = false, available = true
                    )
                }.collect {
                    screen.value = it.copy(writing = writes.isLocked)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                screen.value = screen.value.copy(
                    loading = false, available = false,
                    problem = "Cannot read saved data: " +
                        failure.message?.lineSequence()?.firstOrNull()
                )
            }
        }
    }

    private fun act(action: suspend () -> Unit) {
        viewModelScope.launch {
            if (!writes.tryLock()) return@launch
            screen.value = screen.value.copy(
                writing = true, problem = null
            )
            try { action() }
            catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                screen.value = screen.value.copy(
                    problem = "Write failed: " +
                        failure.message?.lineSequence()?.firstOrNull()
                )
            } finally {
                writes.unlock()
                screen.value = screen.value.copy(writing = false)
            }
        }
    }

    fun refresh() = act {
        store.acceptProductBatch("s2", listOf(
            ProductEntity("camera", "Camera", 119900),
            ProductEntity("tea", "Tea", 650)
        ))
    }
    fun save(note: String) = act {
        store.saveNote("camera", note, 10)
    }
    fun toggleSort() = act {
        preference.set(
            if (screen.value.sort == SavedSort.Latest)
                SavedSort.Title else SavedSort.Latest
        )
    }
    override fun onCleared() { database.close() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        val policy = OpenPolicy.entries.firstOrNull {
            it.name == intent.getStringExtra("policy")
        } ?: OpenPolicy.Migrate
        val owner = ViewModelProvider(
            this, object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(
                    modelClass: Class<T>
                ): T {
                    @Suppress("UNCHECKED_CAST")
                    return ShopOwner(application, policy) as T
                }
            }
        )[ShopOwner::class.java]
        setContent { CommonGoodsTheme { Surface(Modifier.fillMaxSize()) {
            val state by owner.screen.collectAsStateWithLifecycle()
            var note by rememberSaveable {
                mutableStateOf("Compare lenses")
            }
            Column(
                Modifier.fillMaxSize().safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CommonGoodsHeader()
                Text(
                    "Saved",
                    style = MaterialTheme.typography.headlineMedium
                )
                state.problem?.let { ShopMessage(it) }
                if (state.loading) ShopMessage("Reading saved data…")
                if (!state.available && !state.loading) {
                    Button(owner::reload) { Text("Retry read") }
                }
                if (state.available)
                    Text(if (state.sort == SavedSort.Latest)
                        "Latest 50 saved items · Newest first"
                    else "Latest 50 saved items · Title A–Z")
                Button(
                    owner::toggleSort,
                    enabled = state.available && !state.loading &&
                        !state.writing
                ) { Text("Change sort") }
                state.saved.forEach {
                    ProductPhoto(it.id)
                    Text(it.title, style = MaterialTheme.typography.titleLarge)
                    Text("${it.currency} ${it.priceMinor / 100}." +
                        (it.priceMinor % 100).toString().padStart(2, '0'))
                    ShopMessage("Saved ${it.title}: ${it.note}")
                }
                if (state.available && !state.loading &&
                    state.saved.isEmpty()
                ) Text("No saved notes.")
                OutlinedTextField(
                    note, { note = it },
                    label = { Text("Camera note") }, modifier = Modifier.fillMaxWidth()
                )
                Button(
                    { owner.save(note) },
                    enabled = state.products.any { it.id == "camera" } &&
                        state.available && !state.loading && !state.writing
                ) { Text("Save note") }
                LabControls {
                    Text("Saved products · version 2")
                    if (state.available) Text("Recent 50 order: ${state.sort}")
                    state.products.forEach {
                        Text("${it.title}: ${it.currency} ${it.priceMinor} minor units")
                    }
                Button(
                    owner::refresh,
                    enabled = state.available && !state.loading &&
                        !state.writing
                ) { Text("Accept price update") }
                }
            }
        } } }
    }
}
