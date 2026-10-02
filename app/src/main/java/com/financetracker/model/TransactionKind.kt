package com.financetracker.model

/**
 * Everything a row's type can be, which is four things and not three.
 *
 * A bank sends one movement, and it may honestly be read as income, as spending, or as money
 * moving between the user's own accounts. The third is not a type on its own, because arriving
 * and leaving are different facts about the same event — and a row that said "transfer" with
 * no direction would say nothing about which way the money went.
 *
 * So the stored pair is enumerated rather than left as a [TransactionType] and an optional
 * [TransferDirection] that can be combined into combinations nothing means. Enumerating it
 * also gives the type picker a vocabulary to offer instead of a list built at the call site,
 * and gives [of] one place to decide what an unrecognised pair means.
 */
enum class TransactionKind(
    val type: TransactionType,
    /** Null unless [type] is [TransactionType.TRANSFER]: only a transfer has a direction. */
    val direction: TransferDirection?
) {
    INCOME(TransactionType.INCOME, null),
    EXPENSE(TransactionType.EXPENSE, null),

    /** Money that arrived from another of the user's own accounts. */
    TRANSFER_IN(TransactionType.TRANSFER, TransferDirection.IN),

    /** Money that left to another of the user's own accounts. */
    TRANSFER_OUT(TransactionType.TRANSFER, TransferDirection.OUT);

    companion object {
        /**
         * The kind a stored pair stands for.
         *
         * Falls back to [INCOME] rather than throwing, because the only rows that can reach
         * here are ones written by a version of the app that no longer exists or a database
         * edited by hand. A screen that has to draw such a row still can, and the picker
         * offers the user something to change it away from. Income is the fallback because it
         * is the only one of the four whose absence of direction is the default.
         */
        fun of(type: TransactionType, direction: TransferDirection?): TransactionKind =
            entries.firstOrNull { it.type == type && it.direction == direction } ?: INCOME
    }
}