package staff.android.persistence.transfer.exercise

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import staff.android.persistence.transfer.*

fun migrateReports(db: SQLiteConnection) {
    db.execSQL("ALTER TABLE reports RENAME TO legacy_reports")
    db.execSQL(REPORT_DDL)
    db.execSQL(ATTACHMENT_DDL)
    db.execSQL(
        "INSERT INTO reports(id, body, updated_millis) " +
            "SELECT id, body, updated_seconds * 1000 FROM legacy_reports",
    )
    db.execSQL("DROP TABLE legacy_reports")
}
