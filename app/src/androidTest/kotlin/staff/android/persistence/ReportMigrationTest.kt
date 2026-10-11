package staff.android.persistence

import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import staff.android.persistence.transfer.*

abstract class ReportMigrationContract {
    abstract fun migrate(db: SQLiteConnection)

    private fun fixture(rows: List<LegacyReport>, block: (SQLiteConnection) -> Unit) {
        BundledSQLiteDriver().open(":memory:").use { db ->
            createLegacyReports(db, rows)
            block(db)
        }
    }
    private fun reports(db: SQLiteConnection): List<ReportRow> = buildList {
        db.prepare("SELECT id, body, updated_millis FROM reports ORDER BY id").use { q ->
            while (q.step()) add(ReportRow(q.getText(0), q.getText(1), q.getLong(2)))
        }
    }
    private fun attachments(db: SQLiteConnection): List<AttachmentRow> = buildList {
        db.prepare("SELECT report_id, relative_path, state FROM attachments ORDER BY report_id").use { q ->
            while (q.step()) add(AttachmentRow(q.getText(0), q.getText(1), q.getText(2)))
        }
    }
    private fun scalar(db: SQLiteConnection, sql: String): Long =
        db.prepare(sql).use { check(it.step()); it.getLong(0) }

    @Test fun preservesReportsConvertsUnitsAndSeparatesAttachment() {
        fixture(listOf(
            LegacyReport("r1", "Gate inspection", 7, "photos/gate.jpg"),
            LegacyReport("r2", "No photo", 8, null),
        )) { db ->
            upgradeReports(db, ::migrate)
            assertEquals(listOf(ReportRow("r1", "Gate inspection", 7000),
                ReportRow("r2", "No photo", 8000)), reports(db))
            assertEquals(listOf(AttachmentRow("r1", "photos/gate.jpg", "NeedsInspection")),
                attachments(db))
            assertEquals(2L, scalar(db, "PRAGMA user_version"))
        }
    }

    @Test fun missingFileReferenceSurvivesWithoutPretendingItWasInspected() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val path = "migration-${java.util.UUID.randomUUID()}/missing.jpg"
        assertFalse(context.filesDir.resolve(path).exists())
        fixture(listOf(LegacyReport("r1", "Keep reference", 1, path))) { db ->
            upgradeReports(db, ::migrate)
            assertEquals(listOf(AttachmentRow("r1", path, "NeedsInspection")), attachments(db))
        }
    }

    @Test fun invalidPathRollsBackEarlierCopiedRowAndSchema() {
        val invalid = listOf("", " ", "/tmp/photo", "../photo", "photos/../x",
            "photos//x", "photos/./x", "C:\\photo", "a\u0000b")
        for (path in invalid) fixture(listOf(
            LegacyReport("a", "Valid first", 1, "photos/valid.jpg"),
            LegacyReport("z", "Reject later", 2, path),
        )) { db ->
            assertThrows(IllegalArgumentException::class.java) { upgradeReports(db, ::migrate) }
            assertEquals(1L, scalar(db, "PRAGMA user_version"))
            assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM reports WHERE updated_seconds IN (1,2)"))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE name='attachments'"))
            db.prepare("SELECT attachment_path FROM reports WHERE id='z'").use { q ->
                assertTrue(q.step()); assertEquals(path, q.getText(0))
            }
        }
    }

    @Test fun timestampOverflowRollsBackTheWholeUpgrade() {
        for (seconds in listOf(Long.MAX_VALUE / 1000 + 1, Long.MIN_VALUE / 1000 - 1)) {
            fixture(listOf(LegacyReport("a", "First", 1, null),
                LegacyReport("z", "Overflow", seconds, null))) { db ->
                assertThrows(ArithmeticException::class.java) { upgradeReports(db, ::migrate) }
                assertEquals(1L, scalar(db, "PRAGMA user_version"))
                assertEquals(seconds, scalar(db, "SELECT updated_seconds FROM reports WHERE id='z'"))
                assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE name='attachments'"))
            }
        }
    }

    @Test fun negativeRepresentableTimestampIsNotInventedOrClamped() {
        fixture(listOf(LegacyReport("r1", "Before epoch", -1, null))) { db ->
            upgradeReports(db, ::migrate)
            assertEquals(listOf(ReportRow("r1", "Before epoch", -1000)), reports(db))
        }
    }

    @Test fun boundTextPreservesQuotesAndDoesNotTrimRelativeNames() {
        val body = "  Technician's café note \n"
        fixture(listOf(LegacyReport("r'1", body, 2, "photos/ gate.jpg"))) { db ->
            upgradeReports(db, ::migrate)
            assertEquals(ReportRow("r'1", body, 2000), reports(db).single())
            assertEquals(1, attachments(db).size)
            assertEquals("photos/ gate.jpg", attachments(db).single().relativePath)
        }
    }

    @Test fun foreignKeyPreventsDeletingAReferencedReport() {
        fixture(listOf(LegacyReport("r1", "Keep", 1, "photos/x.jpg"))) { db ->
            upgradeReports(db, ::migrate)
            assertEquals(1, attachments(db).size)
            val failure = assertThrows(SQLiteException::class.java) {
                db.execSQL("DELETE FROM reports WHERE id='r1'")
            }
            assertTrue(failure.message.orEmpty().contains("FOREIGN KEY", ignoreCase = true))
            assertEquals(1, reports(db).size)
        }
    }

    @Test fun emptyDatabaseStillUpgradesToCompleteSchema() {
        fixture(emptyList()) { db ->
            upgradeReports(db, ::migrate)
            assertTrue(reports(db).isEmpty())
            assertTrue(attachments(db).isEmpty())
            assertEquals(2L, scalar(db, "PRAGMA user_version"))
        }
    }
}

@RunWith(AndroidJUnit4::class)
class ReportMigrationReferenceTest : ReportMigrationContract() {
    override fun migrate(db: SQLiteConnection) =
        staff.android.persistence.transfer.solution.migrateReports(db)
}

@ExplicitSelection
@RunWith(AndroidJUnit4::class)
class ReportMigrationLearnerTest : ReportMigrationContract() {
    override fun migrate(db: SQLiteConnection) =
        staff.android.persistence.transfer.exercise.migrateReports(db)
}
