package staff.android.persistence.transfer

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

data class LegacyReport(
    val id: String,
    val body: String,
    val updatedSeconds: Long,
    val attachmentPath: String?,
)

data class ReportRow(
    val id: String, val body: String, val updatedMillis: Long,
)
data class AttachmentRow(
    val reportId: String, val relativePath: String, val state: String,
)

val LEGACY_REPORT_DDL = """
    CREATE TABLE reports (
        id TEXT NOT NULL PRIMARY KEY,
        body TEXT NOT NULL,
        updated_seconds INTEGER NOT NULL,
        attachment_path TEXT
    )
""".trimIndent()

val REPORT_DDL = """
    CREATE TABLE reports (
        id TEXT NOT NULL PRIMARY KEY,
        body TEXT NOT NULL,
        updated_millis INTEGER NOT NULL
    )
""".trimIndent()

val ATTACHMENT_DDL = """
    CREATE TABLE attachments (
        report_id TEXT NOT NULL PRIMARY KEY,
        relative_path TEXT NOT NULL,
        state TEXT NOT NULL,
        FOREIGN KEY(report_id) REFERENCES reports(id)
            ON DELETE RESTRICT
    )
""".trimIndent()

fun createLegacyReports(db: SQLiteConnection, rows: List<LegacyReport>) {
    db.execSQL("PRAGMA foreign_keys = ON")
    db.execSQL(LEGACY_REPORT_DDL)
    db.execSQL("PRAGMA user_version = 1")
    db.prepare(
        "INSERT INTO reports(id, body, updated_seconds, " +
            "attachment_path) VALUES (?, ?, ?, ?)",
    ).use { insert ->
        rows.forEach { row ->
            insert.reset()
            insert.clearBindings()
            insert.bindText(1, row.id)
            insert.bindText(2, row.body)
            insert.bindLong(3, row.updatedSeconds)
            if (row.attachmentPath == null) insert.bindNull(4)
            else insert.bindText(4, row.attachmentPath)
            insert.step()
        }
    }
}

fun upgradeReports(
    db: SQLiteConnection,
    migration: (SQLiteConnection) -> Unit,
) {
    check(!db.inTransaction())
    db.execSQL("BEGIN IMMEDIATE")
    try {
        migration(db)
        db.execSQL("PRAGMA user_version = 2")
        db.execSQL("COMMIT")
    } catch (failure: Throwable) {
        db.execSQL("ROLLBACK")
        throw failure
    }
}
