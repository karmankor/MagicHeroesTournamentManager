package cz.magicheroes.tournamentmanager.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ClanEntity::class,
        PlayerEntity::class,
        TournamentEntity::class,
        TournamentParticipantEntity::class,
        MatchEntity::class,
        PointTransactionEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tournamentDao(): TournamentDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "magic_heroes_tournaments.db"
            ).build().also { INSTANCE = it }
        }
    }
}
