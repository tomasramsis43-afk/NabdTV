package com.nabd.tv

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

@Entity(tableName = "items", indices = [Index("kind"), Index("norm")])
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val pk: Long,
    val kind: String,
    val idx: Int,
    val name: String,
    val norm: String,
    val normGrp: String,
    val grp: String,
    val url: String,
    val logo: String?,
    val tvgId: String?,
    val archiveDays: Int,
    val catchupSource: String?,
    val number: Int,
    val rating: String?
)

/** Plain row (no defaults) so Room maps it without constructor ambiguity. */
data class ChannelRow(
    val id: Int, val name: String, val url: String, val logo: String?, val group: String,
    val tvgId: String?, val archiveDays: Int, val catchupSource: String?, val number: Int, val rating: String?
) {
    fun toChannel() = Channel(id, name, url, logo, group, tvgId, archiveDays, catchupSource, number, rating)
}

@Dao
interface ItemDao {
    @Query("SELECT idx AS id, name, url, logo, grp AS `group`, tvgId, archiveDays, catchupSource, number, rating FROM items WHERE kind = :k ORDER BY idx")
    suspend fun channels(k: String): List<ChannelRow>

    @Query("SELECT COUNT(*) FROM items WHERE kind = :k")
    suspend fun count(k: String): Int

    @Query("DELETE FROM items WHERE kind = :k")
    suspend fun clear(k: String)

    @Insert
    suspend fun insert(list: List<ItemEntity>)

    @Transaction
    suspend fun replace(k: String, list: List<ItemEntity>) { clear(k); insert(list) }

    @RawQuery
    suspend fun raw(q: SupportSQLiteQuery): List<ChannelRow>
}

@Database(entities = [ItemEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun items(): ItemDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "nabd.db")
                .fallbackToDestructiveMigration().build().also { inst = it }
        }
    }
}

fun Channel.toEntity(kind: String) = ItemEntity(
    0, kind, id, name, Classifier.normalize(name), Classifier.normalize(group), group, url, logo, tvgId,
    archiveDays, catchupSource, number, rating
)

/** Smart search: accent/hamza-insensitive, every word must match the name or the category. */
suspend fun ItemDao.search(query: String, limit: Int = 300): List<ChannelRow> {
    var tokens = Classifier.normalize(query).split(' ').filter { it.isNotEmpty() }
    if (tokens.isEmpty()) tokens = query.lowercase().split(' ').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return emptyList()
    val where = tokens.joinToString(" AND ") { "(norm LIKE ? OR normGrp LIKE ?)" }
    val bind = ArrayList<Any>()
    tokens.forEach { bind.add("%$it%"); bind.add("%$it%") }
    bind.add("${tokens.first()}%")
    // Names that START with the first word rank first, then by position in the list.
    val sql = "SELECT idx AS id, name, url, logo, grp AS `group`, tvgId, archiveDays, catchupSource, number, rating FROM items " +
        "WHERE $where ORDER BY (CASE WHEN norm LIKE ? THEN 0 ELSE 1 END), idx LIMIT $limit"
    return raw(SimpleSQLiteQuery(sql, bind.toTypedArray()))
}
