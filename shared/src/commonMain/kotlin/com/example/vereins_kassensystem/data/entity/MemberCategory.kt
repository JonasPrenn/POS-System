package com.example.vereins_kassensystem.data.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.vereins_kassensystem.platform.Ids

@Entity(tableName = "member_categories")
data class MemberCategory(
    @PrimaryKey val id: String = Ids.new(),
    val name: String,
    val negativeBalanceLimit: Double, // e.g. -20.0
    @Embedded val sync: SyncMeta = SyncMeta()
)
