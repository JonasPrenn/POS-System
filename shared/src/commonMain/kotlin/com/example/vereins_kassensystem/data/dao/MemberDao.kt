package com.example.vereins_kassensystem.data.dao

import androidx.room.*
import com.example.vereins_kassensystem.data.entity.Member
import kotlinx.coroutines.flow.Flow

@Dao
interface MemberDao {
    @Query("SELECT * FROM members ORDER BY name ASC")
    fun getAllMembers(): Flow<List<Member>>

    @Query("SELECT * FROM members WHERE id = :id")
    suspend fun getMemberById(id: Long): Member?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: Member)

    @Update
    suspend fun updateMember(member: Member)

    @Delete
    suspend fun deleteMember(member: Member)

    @Query("SELECT * FROM members ORDER BY lastUsedTimestamp DESC, name ASC")
    fun getAllMembersSortedByUsage(): Flow<List<Member>>

    @Query("UPDATE members SET lastUsedTimestamp = :timestamp WHERE id = :memberId")
    suspend fun updateLastUsedTimestamp(memberId: Long, timestamp: Long)
    @Query("UPDATE members SET balance = balance + :amount WHERE id = :memberId")
    suspend fun updateBalance(memberId: Long, amount: Double)
}
