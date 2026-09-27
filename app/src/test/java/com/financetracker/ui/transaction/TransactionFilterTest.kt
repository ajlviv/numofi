package com.financetracker.ui.transaction

import com.financetracker.model.BankCode
import com.financetracker.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransactionFilterTest {

    private fun Transaction(
        bankCode: String? = null,
        cardLabel: String? = null
    ) = com.financetracker.model.Transaction(
        title = "TORUS",
        amount = 10.0,
        type = TransactionType.EXPENSE,
        category = "grocery",
        timestamp = 0,
        bankCode = bankCode,
        cardLabel = cardLabel
    )

    @Test
    fun `the bank is canonicalised, so a stray code cannot create a chip`() {
        assertEquals(BankCode.UKRSIBBANK, TransactionFilter(bankCode = " UK ").normalizedBank)
        assertNull(TransactionFilter(bankCode = "paypal").normalizedBank)
        assertNull(TransactionFilter().normalizedBank)
    }

    @Test
    fun `filters are independent, so clearing one leaves the others`() {
        val filter = TransactionFilter(bankCode = BankCode.MONOBANK, type = TransactionType.INCOME)
        assertEquals(
            TransactionFilter(type = TransactionType.INCOME),
            filter.copy(bankCode = null)
        )
    }

    @Test
    fun `changing the bank clears the card with it`() {
        val filter = TransactionFilter(bankCode = BankCode.MONOBANK, cardLabel = "535129****5783")
        assertEquals(
            TransactionFilter(bankCode = BankCode.UKRSIBBANK),
            filter.copy(bankCode = BankCode.UKRSIBBANK, cardLabel = null)
        )
    }

    @Test
    fun `the provenance line omits what is unknown`() {
        val bank = Transaction(bankCode = BankCode.MONOBANK)
        assertEquals("Monobank", bank.provenance())
        assertEquals("Monobank • 4111****2222", bank.copy(cardLabel = "4111****2222").provenance())
        // A hand-entered row has neither, so it gets no line at all.
        assertNull(Transaction().provenance())
    }

    @Test
    fun `an unknown stored code is still shown rather than blanked`() {
        assertEquals("paypal", Transaction(bankCode = "paypal").provenance())
    }
}
