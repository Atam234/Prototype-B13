package com.zeroinstall.relay.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class RelayDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        fun build(context: Context): RelayDatabase =
            Room.databaseBuilder(context.applicationContext, RelayDatabase::class.java, "zeroinstall_relay.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
