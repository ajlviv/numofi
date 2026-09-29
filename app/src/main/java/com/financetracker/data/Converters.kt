package com.financetracker.data

import androidx.room.TypeConverter
import com.financetracker.model.TransactionType
import com.financetracker.model.TransferDirection
import java.time.Instant

class Converters {

    @TypeConverter
    fun fromTransactionType(type: TransactionType): String = type.name

    @TypeConverter
    fun toTransactionType(value: String): TransactionType = TransactionType.valueOf(value)

    @TypeConverter
    fun fromTransferDirection(direction: TransferDirection?): String? = direction?.name

    @TypeConverter
    fun toTransferDirection(value: String?): TransferDirection? = value?.let(TransferDirection::valueOf)

    @TypeConverter
    fun fromInstant(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter
    fun toInstant(millis: Long?): Instant? = millis?.let { Instant.ofEpochMilli(it) }
}