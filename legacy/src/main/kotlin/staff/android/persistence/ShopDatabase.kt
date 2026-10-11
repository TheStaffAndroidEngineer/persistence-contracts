package staff.android.persistence

import android.content.Context
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "price_minor") val priceMinor: Long,
    @ColumnInfo(name = "saved_at") val savedAt: Long?,
    @ColumnInfo(defaultValue = "''") val note: String,
)

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY id")
    fun observe(): Flow<List<ProductEntity>>
    @Upsert suspend fun upsert(products: List<ProductEntity>)
    @Query("UPDATE products SET saved_at = :at, note = :note WHERE id = :id")
    suspend fun save(id: String, note: String, at: Long)
}

@Database(entities = [ProductEntity::class], version = 1, exportSchema = true)
abstract class ShopDatabase : RoomDatabase() {
    abstract fun products(): ProductDao
}

fun openShop(context: Context): ShopDatabase =
    Room.databaseBuilder<ShopDatabase>(context, "shop.db")
        .setDriver(BundledSQLiteDriver()).build()
