package staff.android.persistence.transfer.solution

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import staff.android.persistence.transfer.*

private fun validRelativePath(path: String): Boolean =
    path.isNotBlank() && !path.startsWith('/') &&
        '\\' !in path && '\u0000' !in path && ':' !in path &&
        path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

fun migrateReports(db: SQLiteConnection) {
    db.execSQL("ALTER TABLE reports RENAME TO legacy_reports")
    db.execSQL(REPORT_DDL)
    db.execSQL(ATTACHMENT_DDL)
    db.prepare(
        "SELECT id, body, updated_seconds, attachment_path " +
            "FROM legacy_reports ORDER BY id",
    ).use { rows ->
        while (rows.step()) {
            val id = rows.getText(0)
            val body = rows.getText(1)
            val millis = Math.multiplyExact(rows.getLong(2), 1000L)
            val path = if (rows.isNull(3)) null else rows.getText(3)
            require(path == null || validRelativePath(path)) {
                "Unsupported attachment path for report $id"
            }
            db.prepare(
                "INSERT INTO reports(id, body, updated_millis) " +
                    "VALUES (?, ?, ?)",
            ).use { insert ->
                insert.bindText(1, id)
                insert.bindText(2, body)
                insert.bindLong(3, millis)
                insert.step()
            }
            if (path != null) insertAttachment(db, id, path)
        }
    }
    db.execSQL("DROP TABLE legacy_reports")
}

private fun insertAttachment(
    db: SQLiteConnection, id: String, path: String,
) {
    db.prepare(
        "INSERT INTO attachments(report_id, relative_path, state) " +
            "VALUES (?, ?, ?)",
    ).use { insert ->
        insert.bindText(1, id)
        insert.bindText(2, path)
        insert.bindText(3, "NeedsInspection")
        insert.step()
    }
}
