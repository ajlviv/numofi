package com.financetracker.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The four things a row's type can be.
 *
 * A bank sends one movement and it may honestly be read as income, as spending, or as money
 * moving between the user's own accounts — and the third is not a type on its own, because
 * arriving and leaving are different facts about the same event. So the stored pair is
 * enumerated rather than left as a type and an optional direction that can be combined into
 * combinations nothing means, one of which is a transfer with no direction at all.
 */
class TransactionKindTest {

    @Test
    fun `there are exactly four, and no other combination is expressible`() {
        assertEquals(4, TransactionKind.entries.size)
    }

    @Test
    fun `income and spending carry no direction`() {
        // A direction is meaningless without a transfer, and leaving one on a row would make
        // `transferDirection` mean something other than what its own comment says it means.
        assertEquals(null, TransactionKind.INCOME.direction)
        assertEquals(null, TransactionKind.EXPENSE.direction)
    }

    @Test
    fun `a transfer is either arriving or leaving`() {
        assertEquals(TransactionType.TRANSFER, TransactionKind.TRANSFER_IN.type)
        assertEquals(TransferDirection.IN, TransactionKind.TRANSFER_IN.direction)
        assertEquals(TransactionType.TRANSFER, TransactionKind.TRANSFER_OUT.type)
        assertEquals(TransferDirection.OUT, TransactionKind.TRANSFER_OUT.direction)
    }

    @Test
    fun `a stored row reads back as the kind it already is`() {
        for (kind in TransactionKind.entries) {
            assertEquals(kind, TransactionKind.of(kind.type, kind.direction))
        }
    }

    @Test
    fun `an unrecognised pair resolves to income rather than throwing`() {
        // Only reachable from a row written by a future version of the app, or a hand-edited
        // database. A screen that has to render the row still can, and the picker will show
        // the user something to change it away from.
        assertEquals(TransactionKind.INCOME, TransactionKind.of(TransactionType.TRANSFER, null))
    }
}