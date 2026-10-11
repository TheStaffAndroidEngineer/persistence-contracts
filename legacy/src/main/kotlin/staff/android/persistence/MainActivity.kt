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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LegacyOwner(app: Application) : AndroidViewModel(app) {
    private val database = openShop(app)
    val products = database.products().observe().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun seed() { viewModelScope.launch {
        database.products().upsert(listOf(
            ProductEntity("camera","Camera",129900,10,"Compare lenses"),
            ProductEntity("tea","Tea",600,null,"")))
    } }
    fun save(note: String) { viewModelScope.launch { database.products().save("camera",note,10) } }
    override fun onCleared() { database.close() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        val owner = ViewModelProvider(this)[LegacyOwner::class.java]
        setContent { CommonGoodsTheme { Surface(Modifier.fillMaxSize()) {
            val products by owner.products.collectAsStateWithLifecycle()
            var note by rememberSaveable { mutableStateOf("Compare lenses") }
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CommonGoodsHeader()
                Text("Saved", style = MaterialTheme.typography.headlineLarge)
                Text("Keep the details you want to come back to.")
                products.filter { it.savedAt != null }.forEach {
                    ProductPhoto(it.id)
                    Text(it.title, style = MaterialTheme.typography.titleLarge)
                    Text("USD ${it.priceMinor / 100}." + (it.priceMinor % 100).toString().padStart(2, '0'))
                }
                if (products.isEmpty()) ShopMessage("Your saved collection is empty.")
                products.firstOrNull { it.id == "camera" }?.let {
                    ShopMessage("Saved note: ${it.note}")
                }
                OutlinedTextField(note,{ note=it },label={Text("Camera note")}, modifier = Modifier.fillMaxWidth())
                Button({ owner.save(note) },enabled=products.isNotEmpty()) { Text("Save note") }
                LabControls {
                    Text("Saved products · version 1")
                    Button(owner::seed) { Text("Seed version 1") }
                    products.forEach { Text("${it.title}: ${it.priceMinor} minor units") }
                    Text("Install version 2 without clearing app data to test the upgrade.")
                }
            }
        } } }
    }
}
