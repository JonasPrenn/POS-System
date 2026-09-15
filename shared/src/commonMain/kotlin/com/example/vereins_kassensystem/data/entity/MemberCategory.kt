package com.example.vereins_kassensystem.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "member_categories")
data class MemberCategory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val negativeBalanceLimit: Double // e.g. -20.0
)
