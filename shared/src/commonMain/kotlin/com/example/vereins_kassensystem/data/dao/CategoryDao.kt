package com.example.vereins_kassensystem.data.dao

import androidx.room.*
import com.example.vereins_kassensystem.data.entity.MemberCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM member_categories ORDER BY name ASC")
    fun getAllCategories(): Flow<List<MemberCategory>>

    @Query("SELECT * FROM member_categories WHERE id = :id")
    suspend fun getCategoryById(id: Long): MemberCategory?

    @Query("SELECT * FROM member_categories WHERE name = :name")
    suspend fun getCategoryByName(name: String): MemberCategory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: MemberCategory): Long

    @Update
    suspend fun updateCategory(category: MemberCategory)

    @Delete
    suspend fun deleteCategory(category: MemberCategory)
}
