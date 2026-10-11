package staff.android.persistence

import android.content.Context
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "price_minor") val priceMinor: Long,
    @ColumnInfo(defaultValue = "'USD'") val currency: String = "USD",
)

@Entity(tableName = "saved_products", foreignKeys = [ForeignKey(
    entity = ProductEntity::class, parentColumns = ["id"],
    childColumns = ["product_id"], onDelete = ForeignKey.RESTRICT,
)], indices = [Index(value = ["saved_at", "product_id"],
    orders = [Index.Order.DESC, Index.Order.ASC])])
data class SavedEntity(
    @PrimaryKey @ColumnInfo(name = "product_id") val productId: String,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
    val note: String,
)

@Entity(tableName = "accepted_batch")
data class BatchReceiptEntity(
    @PrimaryKey val slot: Int = 0, val revision: String
)

data class SavedRow(
    val id: String, val title: String,
    @ColumnInfo(name = "price_minor") val priceMinor: Long,
    val currency: String,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
    val note: String,
)

const val SAVED_QUERY = """SELECT p.id, p.title, p.price_minor,
        p.currency, s.saved_at, s.note
        FROM saved_products AS s JOIN products AS p ON p.id = s.product_id
        WHERE s.saved_at >= :since
        ORDER BY s.saved_at DESC, s.product_id ASC LIMIT :limit"""

@Dao
interface ShopDao {
    @Query("SELECT * FROM products ORDER BY id")
    fun observeProducts(): Flow<List<ProductEntity>>
    @Query(SAVED_QUERY)
    fun observeSaved(since: Long, limit: Int): Flow<List<SavedRow>>
    @Upsert suspend fun upsertProducts(products: List<ProductEntity>)
    @Upsert suspend fun save(value: SavedEntity)
    @Upsert suspend fun receipt(value: BatchReceiptEntity)
    @Query("SELECT * FROM accepted_batch WHERE slot = 0")
    suspend fun receipt(): BatchReceiptEntity?
    @Query("SELECT * FROM saved_products WHERE product_id = :id")
    suspend fun saved(id: String): SavedEntity?
    @Query("SELECT * FROM products ORDER BY id")
    suspend fun products(): List<ProductEntity>
}

@Database(
    entities = [ProductEntity::class, SavedEntity::class,
        BatchReceiptEntity::class],
    version = 2, exportSchema = true
)
abstract class ShopDatabase : RoomDatabase() { abstract fun shop(): ShopDao }

val MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.prepare("""SELECT COUNT(*) FROM products
            WHERE saved_at IS NULL AND note <> ''""").use {
            check(it.step())
            check(it.getLong(0) == 0L) {
                "Legacy note has no saved ownership; migration refused"
            }
        }
        connection.execSQL("ALTER TABLE products RENAME TO products_v1")
        connection.execSQL("""CREATE TABLE products (
            id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL,
            price_minor INTEGER NOT NULL,
            currency TEXT NOT NULL DEFAULT 'USD')""")
        connection.execSQL("""INSERT INTO products(
            id,title,price_minor,currency)
            SELECT id,title,price_minor,'USD' FROM products_v1""")
        connection.execSQL("""CREATE TABLE saved_products (
            product_id TEXT NOT NULL PRIMARY KEY,
            saved_at INTEGER NOT NULL,
            note TEXT NOT NULL,
            FOREIGN KEY(product_id) REFERENCES products(id)
            ON DELETE RESTRICT)""")
        connection.execSQL("""INSERT INTO saved_products(
            product_id,saved_at,note)
            SELECT id,saved_at,note FROM products_v1
            WHERE saved_at IS NOT NULL""")
        connection.execSQL("""CREATE INDEX
            index_saved_products_saved_at_product_id
            ON saved_products(saved_at DESC, product_id ASC)""")
        connection.execSQL("""CREATE TABLE accepted_batch (
            slot INTEGER NOT NULL PRIMARY KEY,
            revision TEXT NOT NULL)""")
        connection.execSQL("""INSERT INTO accepted_batch(slot,revision)
            VALUES(0,'legacy-v1')""")
        connection.execSQL("DROP TABLE products_v1")
    }
}

enum class OpenPolicy { Migrate, Missing, Destructive }

fun openShop(context: Context, name: String = "shop.db",
             policy: OpenPolicy = OpenPolicy.Migrate): ShopDatabase {
    val builder = Room.databaseBuilder<ShopDatabase>(context, name)
        .setDriver(BundledSQLiteDriver())
    when (policy) {
        OpenPolicy.Migrate -> builder.addMigrations(MIGRATION_1_2)
        OpenPolicy.Missing -> Unit
        OpenPolicy.Destructive ->
            builder.fallbackToDestructiveMigration(
                dropAllTables = true
            )
    }
    return builder.build()
}
