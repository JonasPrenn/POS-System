package com.example.vereins_kassensystem.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.data.entity.MemberRow
import kotlinx.coroutines.flow.Flow

@Dao
interface MemberDao {
    /** Zuletzt benutzte zuerst — wer gerade an der Theke stand, steht gleich wieder dort. */
    @Query("$MEMBER_SELECT WHERE m.deleted = 0 ORDER BY lastUsedTimestamp DESC, m.name ASC")
    fun getAllMembersSortedByUsage(): Flow<List<Member>>

    @Query("$MEMBER_SELECT WHERE m.id = :id AND m.deleted = 0")
    suspend fun getMemberById(id: String): Member?

    @Query("SELECT * FROM members WHERE id = :id")
    suspend fun getRow(id: String): MemberRow?

    @Query("SELECT COUNT(*) FROM members WHERE categoryId = :categoryId AND deleted = 0")
    suspend fun countInCategory(categoryId: String): Int

    @Insert
    suspend fun insert(row: MemberRow)

    @Update
    suspend fun update(row: MemberRow)

    @Query("UPDATE members SET deleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)
}
