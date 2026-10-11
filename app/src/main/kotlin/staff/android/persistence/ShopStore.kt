package staff.android.persistence

import androidx.room3.useReaderConnection
import androidx.room3.withWriteTransaction

class ShopStore(private val database: ShopDatabase) {
    val saved = database.shop().observeSaved(since = 0, limit = 50)
    val products = database.shop().observeProducts()

    suspend fun acceptProductBatch(revision: String, products: List<ProductEntity>) {
        require(revision.isNotBlank())
        require(products.map { it.id }.distinct().size == products.size)
        require(products.all { it.id.isNotBlank() && it.title.isNotBlank() &&
            it.priceMinor in 0L..100_000_000L && it.currency == "USD" })
        database.withWriteTransaction {
            database.shop().upsertProducts(products)
            database.shop().receipt(BatchReceiptEntity(revision = revision))
        }
    }

    suspend fun saveNote(productId: String, note: String, savedAt: Long) {
        require(savedAt >= 0)
        database.shop().save(SavedEntity(productId, savedAt, note))
    }

    suspend fun savedQueryPlan(since: Long): List<String> =
        database.useReaderConnection { connection ->
            connection.usePrepared("EXPLAIN QUERY PLAN " + SAVED_QUERY) { statement ->
                statement.bindLong(1, since)
                statement.bindLong(2, 50)
                buildList { while (statement.step()) add(statement.getText(3)) }
            }
        }
}
