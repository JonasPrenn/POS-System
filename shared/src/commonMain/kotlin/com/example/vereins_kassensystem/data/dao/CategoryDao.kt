package com.example.vereins_kassensystem.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.vereins_kassensystem.data.entity.MemberCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM member_categories WHERE deleted = 0 ORDER BY name ASC")
    fun getAllCategories(): Flow<List<MemberCategory>>

    @Query("SELECT * FROM member_categories WHERE id = :id")
    suspend fun getCategoryById(id: String): MemberCategory?

    @Query("SELECT * FROM member_categories WHERE name = :name AND deleted = 0")
    suspend fun getCategoryByName(name: String): MemberCategory?

    @Insert
    suspend fun insert(category: MemberCategory)

    @Update
    suspend fun update(category: MemberCategory)

    @Query("UPDATE member_categories SET deleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)
}
