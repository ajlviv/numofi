package com.financetracker.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.*

@Entity(
    tableName = "transactions",
    indices = [Index(value = ["userId", "externalId"], unique = false)]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val title: String,
    val amount: Double,
    val type: TransactionType,
    val category: String,
    val timestamp: Long = System.currentTimeMillis(),
    val note: String? = null,
    /**
     * Identifier assigned by the bank, when this row came from an import. Unique per
     * (userId, bank) in practice, so re-syncing an overlapping window is a no-op
     * instead of duplicating rows. Null for rows the user created by hand.
     */
    val externalId: String? = null,
    val source: String? = null,
    /**
     * ISO 4217 alphabetic code this amount is denominated in, e.g. "UAH". Null means
     * unknown, which is only allowed for hand-entered rows; imported rows always carry
     * the currency the bank reported, so totals and labels never guess from device locale.
     */
    val currencyCode: String? = null,
    /**
     * Which bank this row belongs to, see [BankCode]. Null only for hand-entered rows, and
     * for statement files whose bank could not be established.
     */
    val bankCode: String? = null,
    /**
     * The card the bank disclosed for this row, e.g. a masked PAN. Null when the bank
     * publishes no card, or when the statement reports account-level movements. Never
     * inferred: a wrong label is worse than a missing one.
     */
    val cardLabel: String? = null,
    /**
     * Lowercased haystack the search query matches against, see [SearchText].
     *
     * Every write path fills this in, so a null means the row was not written by this app.
     * The column still allows one because it was added to a table that already held rows,
     * and nothing here can take that back.
     */
    val searchText: String? = null,
    /** Only a [TransactionType.TRANSFER] has a direction, so this is null for everything else. */
    val transferDirection: TransferDirection? = null
) {
    companion object {
        /**
         * [bank] is passed rather than resolved here because this is a static factory with
         * no access to the bank list, and a name is what the search haystack needs. It is
         * a required argument rather than a defaulted one so that a caller cannot add a
         * bank to a row and silently leave the row unfindable by that bank's name.
         *
         * The entry form passes the bank the user picked, or null when they picked none,
         * which is what leaves a hand-entered row without one.
         */
        fun fromDomain(
            transaction: Transaction,
            userId: String,
            bank: BankRef?
        ): TransactionEntity {
            return TransactionEntity(
                id = transaction.id,
                userId = userId,
                title = transaction.title,
                amount = transaction.amount,
                type = transaction.type,
                category = transaction.category,
                timestamp = transaction.timestamp,
                note = transaction.note,
                externalId = transaction.externalId,
                source = transaction.source,
                currencyCode = transaction.currencyCode,
                bankCode = bank?.code ?: transaction.bankCode,
                cardLabel = transaction.cardLabel,
                transferDirection = transaction.transferDirection,
                // Derived here rather than at each call site, so no writer can forget it
                // and leave the row permanently unfindable.
                searchText = SearchText.of(
                    transaction.title,
                    transaction.note,
                    transaction.category,
                    bank,
                    transaction.cardLabel
                )
            )
        }
    }

    fun toDomain(): Transaction {
        return Transaction(
            id = id,
            title = title,
            amount = amount,
            type = type,
            category = category,
            timestamp = timestamp,
            note = note,
            currencyCode = currencyCode,
            externalId = externalId,
            source = source,
            bankCode = bankCode,
            cardLabel = cardLabel,
            transferDirection = transferDirection
        )
    }
}

enum class TransactionType {
    INCOME,
    EXPENSE,

    /**
     * Money moving between the user's own pockets rather than being earned or spent.
     *
     * Buying a bond is the case this exists for. Recording it as an expense would inflate
     * spending, which is the figure the app reports most, and recording it as neither would
     * leave the cash balance claiming money that had left the account.
     */
    TRANSFER
}

/** Which way a [TransactionType.TRANSFER] moved. Null for any other type. */
enum class TransferDirection {
    /** Left the account, e.g. a bond purchase. */
    OUT,

    /** Arrived in the account, e.g. a bond sale. */
    IN
}

data class Transaction(
    val id: Long = 0,
    val title: String,
    val amount: Double,
    val type: TransactionType,
    val category: String,
    val timestamp: Long,
    val note: String? = null,
    val currencyCode: String? = null,
    /** See [TransactionEntity.externalId]; null for hand-entered rows. */
    val externalId: String? = null,
    /**
     * Which subsystem wrote the row. Carried through to the domain type because the list
     * shows the bank a row came from, and dropping it here is what made that impossible.
     */
    val source: String? = null,
    val bankCode: String? = null,
    val cardLabel: String? = null,
    /**
     * Which way a [TransactionType.TRANSFER] moved. Null for income and expense, and for a
     * transfer whose direction was not recorded — counted as neither an inflow nor an
     * outflow rather than guessed at.
     */
    val transferDirection: TransferDirection? = null
) {
    fun isIncome(): Boolean = type == TransactionType.INCOME
    fun isExpense(): Boolean = type == TransactionType.EXPENSE
    fun isTransfer(): Boolean = type == TransactionType.TRANSFER

    /**
     * Money that arrived in the account, counting a transfer in.
     *
     * Separate from [isIncome] so that the import paths and the summary panel keep meaning
     * exactly what they meant before transfers existed.
     */
    fun isCashInflow(): Boolean =
        isIncome() || (isTransfer() && transferDirection == TransferDirection.IN)

    /** Money that left the account, counting a transfer out. */
    fun isCashOutflow(): Boolean =
        isExpense() || (isTransfer() && transferDirection == TransferDirection.OUT)
}

/**
 * A card the user has actually transacted with, paired with the bank that issued it.
 *
 * The list screen needs the pairing for two things: to offer only the cards belonging to the
 * selected banks, and to drop a selected card when a change of banks rules it out. Deriving
 * both from this one small query keeps that decision pure and synchronous, rather than
 * reconciling against a card list that arrives a frame after the bank selection changed.
 */
data class CardRef(
    val bankCode: String?,
    val cardLabel: String
)

data class User(
    val uid: String,
    val email: String,
    val displayName: String?,
    val photoUrl: String?
)

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val uid: String,
    val email: String,
    val displayName: String?,
    val photoUrl: String?
) {
    companion object {
        fun fromDomain(user: User): UserEntity {
            return UserEntity(
                uid = user.uid,
                email = user.email,
                displayName = user.displayName,
                photoUrl = user.photoUrl
            )
        }
    }

    fun toDomain(): User {
        return User(
            uid = uid,
            email = email,
            displayName = displayName,
            photoUrl = photoUrl
        )
    }
}

sealed class AuthState {
    object Loading : AuthState()
    data class Authenticated(val user: User) : AuthState()
    data class Unauthenticated(val error: String? = null) : AuthState()
}