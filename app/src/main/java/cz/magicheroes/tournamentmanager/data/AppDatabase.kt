package cz.magicheroes.tournamentmanager.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ClanEntity::class,
        PlayerEntity::class,
        TournamentEntity::class,
        TournamentParticipantEntity::class,
        MatchEntity::class,
        PointTransactionEntity::class,
        ClanPointAdjustmentEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tournamentDao(): TournamentDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tournament_participants ADD COLUMN isQueued INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE players ADD COLUMN eloRating INTEGER NOT NULL DEFAULT 1000")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tournament_participants ADD COLUMN isWithdrawn INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE matches ADD COLUMN manualPlayer1Locked INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE matches ADD COLUMN manualPlayer1Id INTEGER")
                database.execSQL("ALTER TABLE matches ADD COLUMN manualPlayer1AssignedAt INTEGER")
                database.execSQL("ALTER TABLE matches ADD COLUMN manualPlayer2Locked INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE matches ADD COLUMN manualPlayer2Id INTEGER")
                database.execSQL("ALTER TABLE matches ADD COLUMN manualPlayer2AssignedAt INTEGER")
                database.execSQL("ALTER TABLE matches ADD COLUMN routedWinnerMatchNumber INTEGER")
                database.execSQL("ALTER TABLE matches ADD COLUMN routedLoserMatchNumber INTEGER")
                database.execSQL("CREATE TABLE IF NOT EXISTS clan_point_adjustments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, clanIdSnapshot INTEGER NOT NULL, clanNameSnapshot TEXT NOT NULL, points INTEGER NOT NULL, reason TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_clan_point_adjustments_clanIdSnapshot ON clan_point_adjustments (clanIdSnapshot)")
            }
        }

        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "magic_heroes_tournaments.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { INSTANCE = it }
        }
    }
}
