package sh.haven.core.data.db

import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import android.content.Context

@RunWith(RobolectricTestRunner::class)
class HavenDatabaseMigrationTest {
    @Test
    fun testMigration80To81() {
        val context = RuntimeEnvironment.getApplication()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test-migration.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(80) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    // Create connection_profiles table
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `connection_profiles` (
                            `id` TEXT NOT NULL,
                            `label` TEXT NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """)
                    // Create port_forward_rules table
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `port_forward_rules` (
                            `id` TEXT NOT NULL,
                            `profileId` TEXT NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """)
                    // Create connection_logs table
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `connection_logs` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `profileId` TEXT NOT NULL
                        )
                    """)
                    // Create workspace_item table
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `workspace_item` (
                            `id` TEXT NOT NULL,
                            `connectionProfileId` TEXT,
                            PRIMARY KEY(`id`)
                        )
                    """)
                }

                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase

        // Insert a valid profile
        db.execSQL("INSERT INTO connection_profiles (id, label) VALUES ('profile-1', 'My Server')")

        // Insert a valid and an orphan port forward rule
        db.execSQL("INSERT INTO port_forward_rules (id, profileId) VALUES ('pf-1', 'profile-1')")
        db.execSQL("INSERT INTO port_forward_rules (id, profileId) VALUES ('pf-2', 'profile-orphan')")

        // Insert a valid and an orphan connection log
        db.execSQL("INSERT INTO connection_logs (id, profileId) VALUES (1, 'profile-1')")
        db.execSQL("INSERT INTO connection_logs (id, profileId) VALUES (2, 'profile-orphan')")

        // Insert workspace items
        db.execSQL("INSERT INTO workspace_item (id, connectionProfileId) VALUES ('ws-1', 'profile-1')")
        db.execSQL("INSERT INTO workspace_item (id, connectionProfileId) VALUES ('ws-2', 'profile-orphan')")
        db.execSQL("INSERT INTO workspace_item (id, connectionProfileId) VALUES ('ws-3', NULL)")

        // Run migration
        HavenDatabase.MIGRATION_80_81.migrate(db)

        // Assert port_forward_rules orphans are deleted
        db.query("SELECT id FROM port_forward_rules").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("pf-1", cursor.getString(0))
        }

        // Assert connection_logs orphans are deleted
        db.query("SELECT id FROM connection_logs").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(1, cursor.getLong(0))
        }

        // Assert workspace_item connectionProfileId is set to NULL for orphans
        db.query("SELECT id, connectionProfileId FROM workspace_item ORDER BY id").use { cursor ->
            assertEquals(3, cursor.count)
            
            cursor.moveToFirst() // ws-1
            assertEquals("ws-1", cursor.getString(0))
            assertEquals("profile-1", cursor.getString(1))

            cursor.moveToNext() // ws-2
            assertEquals("ws-2", cursor.getString(0))
            org.junit.Assert.assertNull(cursor.getString(1))

            cursor.moveToNext() // ws-3
            assertEquals("ws-3", cursor.getString(0))
            org.junit.Assert.assertNull(cursor.getString(1))
        }

        db.close()
    }
}
