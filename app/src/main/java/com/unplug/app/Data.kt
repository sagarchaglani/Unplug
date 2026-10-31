package com.unplug.app

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity
data class HeldNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String, val app: String, val sender: String,
    val text: String, val time: Long, val reason: String
)

/** type = "VIP" (sender name or package contains value) or "KEYWORD" */
@Entity
data class Rule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, val value: String
)

@Dao
interface UnplugDao {
    @Insert suspend fun insertHeld(h: HeldNotification)
    @Query("SELECT * FROM HeldNotification ORDER BY time DESC") fun held(): Flow<List<HeldNotification>>
    @Query("SELECT COUNT(*) FROM HeldNotification WHERE time >= :since") suspend fun countSince(since: Long): Int
    @Query("DELETE FROM HeldNotification") suspend fun clearHeld()
    @Query("DELETE FROM HeldNotification WHERE time < :t") suspend fun purgeOlder(t: Long)

    @Insert suspend fun insertRule(r: Rule)
    @Delete suspend fun deleteRule(r: Rule)
    @Query("SELECT * FROM Rule ORDER BY id") fun rules(): Flow<List<Rule>>
    @Query("SELECT * FROM Rule") suspend fun rulesNow(): List<Rule>
}

@Database(entities = [HeldNotification::class, Rule::class], version = 1, exportSchema = false)
abstract class UnplugDb : RoomDatabase() {
    abstract fun dao(): UnplugDao
    companion object {
        @Volatile private var inst: UnplugDb? = null
        fun get(c: Context): UnplugDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, UnplugDb::class.java, "unplug.db")
                .build().also { inst = it }
        }
    }
}

/** Session state lives in SharedPreferences so the listener and service can read it cheaply. */
object SessionPrefs {
    private fun p(c: Context) = c.getSharedPreferences("unplug", Context.MODE_PRIVATE)
    fun begin(c: Context, minutes: Int, commitment: Boolean) {
        val now = System.currentTimeMillis()
        p(c).edit().putLong("start", now).putLong("end", now + minutes * 60_000L)
            .putBoolean("commit", commitment).apply()
    }
    fun start(c: Context) = p(c).getLong("start", 0)
    fun end(c: Context) = p(c).getLong("end", 0)
    fun commitment(c: Context) = p(c).getBoolean("commit", false)
    fun isActive(c: Context) = end(c) > System.currentTimeMillis()
    fun clear(c: Context) = p(c).edit().putLong("start", 0).putLong("end", 0).apply()
    fun seeded(c: Context) = p(c).getBoolean("seeded", false)
    fun markSeeded(c: Context) = p(c).edit().putBoolean("seeded", true).apply()
}
