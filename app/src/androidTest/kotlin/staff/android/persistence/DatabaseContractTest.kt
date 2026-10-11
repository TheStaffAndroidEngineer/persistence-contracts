package staff.android.persistence

import androidx.room3.testing.MigrationTestHelper
import androidx.room3.withWriteTransaction
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DatabaseContractTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "chapter12-test.db"
    private val file = context.getDatabasePath(name)
    @get:Rule val helper = MigrationTestHelper(
        instrumentation = instrumentation,
        file = file,
        driver = BundledSQLiteDriver(),
        databaseClass = ShopDatabase::class,
    )

    private fun seed(connection: SQLiteConnection, orphan: Boolean = false) {
        connection.prepare("INSERT INTO products(id,title,price_minor,saved_at,note) VALUES(?,?,?,?,?)").use {
            it.bindText(1, "camera"); it.bindText(2, "Camera"); it.bindLong(3,129900)
            if (orphan) it.bindNull(4) else it.bindLong(4,10)
            it.bindText(5,"  Compare lenses — don't delete  "); it.step()
        }
        connection.execSQL("INSERT INTO products(id,title,price_minor,saved_at,note) VALUES('tea','Tea',600,NULL,'')")
    }
    @After fun clean() { context.deleteDatabase(name) }

    @Test fun migrationPreservesValuesOwnershipAndIntegrity() = runBlocking {
        helper.createDatabase(1).use { seed(it) }
        helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2)).use { db ->
            db.prepare("SELECT p.price_minor,p.currency,s.saved_at,s.note FROM products p JOIN saved_products s ON p.id=s.product_id WHERE p.id='camera'").use {
                assertTrue(it.step()); assertEquals(129900L,it.getLong(0));assertEquals("USD",it.getText(1))
                assertEquals(10L,it.getLong(2));assertEquals("  Compare lenses — don't delete  ",it.getText(3))
                assertFalse(it.step())
            }
            db.prepare("SELECT COUNT(*) FROM products").use { assertTrue(it.step());assertEquals(2L,it.getLong(0)) }
            db.prepare("SELECT COUNT(*) FROM saved_products").use { assertTrue(it.step());assertEquals(1L,it.getLong(0)) }
            db.prepare("SELECT COUNT(*) FROM saved_products WHERE product_id='tea'").use { assertTrue(it.step());assertEquals(0L,it.getLong(0)) }
            db.execSQL("PRAGMA foreign_keys=ON")
            try { db.execSQL("DELETE FROM products WHERE id='camera'");fail("saved product deletion must be restricted") }
            catch (failure: android.database.SQLException) { assertTrue(failure.message!!.contains("FOREIGN KEY",ignoreCase=true)) }
            db.prepare("PRAGMA foreign_key_check").use { assertFalse(it.step()) }
            db.prepare("PRAGMA integrity_check").use { assertTrue(it.step());assertEquals("ok",it.getText(0)) }
        }
    }

    @Test fun unsupportedLegacyOwnershipFailsWithoutDeletingOriginalData() = runBlocking {
        helper.createDatabase(1).use { seed(it, orphan = true) }
        try { helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2));fail("must refuse orphan note") }
        catch (failure: IllegalStateException) { assertTrue(failure.message!!.contains("Legacy note")) }
        BundledSQLiteDriver().open(file.absolutePath).use { db ->
            db.prepare("PRAGMA user_version").use { assertTrue(it.step());assertEquals(1L,it.getLong(0)) }
            db.prepare("SELECT note FROM products WHERE id='camera'").use {
                assertTrue(it.step());assertEquals("  Compare lenses — don't delete  ",it.getText(0))
            }
        }
    }

    @Test fun missingMigrationFailsAndDestructivePolicyLosesTheNote() = runBlocking {
        helper.createDatabase(1).use { seed(it) }
        withShop(OpenPolicy.Missing) { db ->
            try { db.shop().products();fail("missing path must fail") }
            catch (failure: IllegalStateException) { assertTrue(failure.message!!.contains("migration",ignoreCase=true)) }
        }
        withShop(OpenPolicy.Destructive) { db ->
            assertTrue(db.shop().products().isEmpty())
            assertNull(db.shop().saved("camera"))
        }
    }

    @Test fun productRefreshPreservesNoteAndTransactionRollsBackBothParts() = runBlocking {
        helper.createDatabase(1).use { seed(it) }
        withShop() { db ->
            db.useWriterConnection { connection ->
                connection.usePrepared("PRAGMA foreign_keys") {
                    assertTrue(it.step());assertEquals(1L,it.getLong(0))
                }
                try { connection.usePrepared("DELETE FROM products WHERE id='camera'") { it.step() };fail("Room must enforce saved ownership") }
                catch (failure: android.database.SQLException) { assertTrue(failure.message!!.contains("FOREIGN KEY",ignoreCase=true)) }
            }
            val store = ShopStore(db)
            store.acceptProductBatch("s2",listOf(ProductEntity("camera","Camera",119900)))
            assertEquals(119900L,db.shop().products().first { it.id=="camera" }.priceMinor)
            assertEquals("  Compare lenses — don't delete  ",db.shop().saved("camera")!!.note)
            try {
                db.withWriteTransaction {
                    db.shop().upsertProducts(listOf(ProductEntity("camera","Camera",1)))
                    db.shop().receipt(BatchReceiptEntity(revision="never-commit"))
                    throw InterruptedAcceptance()
                }
            } catch (_: InterruptedAcceptance) { }
            assertEquals(119900L,db.shop().products().first { it.id=="camera" }.priceMinor)
            assertEquals("s2",db.shop().receipt()!!.revision)
        }
    }

    @Test fun indexChangesPlanWithoutChangingStableOrderedRows() = runBlocking {
        withShop() { db ->
            val store = ShopStore(db)
            store.acceptProductBatch("s1",listOf(ProductEntity("camera","Camera",129900),ProductEntity("tea","Tea",600)))
            store.saveNote("tea","Tea note",10);store.saveNote("camera","Camera note",10)
            assertEquals(listOf("camera","tea"),store.saved.first().map { it.id })
            val sqliteVersion = db.useWriterConnection { c ->
                c.usePrepared("SELECT sqlite_version()") { it.step();it.getText(0) }
            }
            println("SQLite version: $sqliteVersion; since=0; limit=50")
            val indexed = store.savedQueryPlan(0)
            val unindexed = db.useWriterConnection { c ->
                c.usePrepared("DROP INDEX index_saved_products_saved_at_product_id") { it.step() }
                c.usePrepared("EXPLAIN QUERY PLAN " + SAVED_QUERY) { statement ->
                    statement.bindLong(1,0); statement.bindLong(2,50)
                    buildList { while (statement.step()) add(statement.getText(3)) }
                }
            }
            println("INDEXED: $indexed\nWITHOUT INDEX: $unindexed")
            assertTrue(indexed.any { it.contains("index_saved_products_saved_at_product_id") })
            assertTrue(unindexed.any { it.contains("TEMP B-TREE") })
            assertEquals(listOf("camera","tea"),store.saved.first().map { it.id })
            println("INDEXED: $indexed\nWITHOUT INDEX: $unindexed")
        }
    }
    private suspend fun <T> withShop(policy: OpenPolicy = OpenPolicy.Migrate,
        block: suspend (ShopDatabase) -> T): T {
        val db = openShop(context, name, policy)
        try { return block(db) } finally { db.close() }
    }
    private class InterruptedAcceptance : RuntimeException()
}
