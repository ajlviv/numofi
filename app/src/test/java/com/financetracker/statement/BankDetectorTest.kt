package com.financetracker.statement
import com.financetracker.data.statement.BankDetector

import com.financetracker.model.BankCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BankDetectorTest {

    /**
     * The titles the Ukrsibbank statement really carries, as opposed to a set invented for
     * a test. Only the account-currency column is actually distinctive.
     */
    private val ukrsibbankHeader = listOf(
        "Дата операції", "Опис операції", "Сума в валюті рахунку", "Валюта"
    )

    @Test
    fun `recognises the Ukrsibbank card statement by its column titles`() {
        assertEquals(BankCode.UKRSIBBANK, BankDetector.detect(listOf(ukrsibbankHeader)))
    }

    @Test
    fun `recognises the PrivatBank export by its column titles`() {
        val table = listOf(
            listOf("Дата", "Картка", "Опис операції", "Сума в валюті транзакції", "Валюта транзакції")
        )
        assertEquals(BankCode.PRIVATBANK, BankDetector.detect(table))
    }

    @Test
    fun `finds the PrivatBank header sitting below the statement title`() {
        val table = listOf(
            listOf("Історія операцій за період 27.08.2026 - 27.09.2026"),
            listOf("Дата", "Картка", "Опис операції", "Валюта транзакції")
        )
        assertEquals(BankCode.PRIVATBANK, BankDetector.detect(table))
    }

    @Test
    fun `the shared operation description column does not pick a bank`() {
        val table = listOf(listOf("Дата операції", "Опис операції", "Сума", "Валюта"))
        assertNull(BankDetector.detect(table))
    }

    @Test
    fun `a title that merely mentions an account is not treated as a header`() {
        val table = listOf(listOf("Виписка по рахунку"), listOf(""))
        assertNull(BankDetector.detect(table))
    }

    @Test
    fun `an unrecognised export is left for the user to choose`() {
        assertNull(BankDetector.detect(listOf(listOf("Date", "Description", "Amount"))))
    }

    @Test
    fun `an empty table is not attributed to anything`() {
        assertNull(BankDetector.detect(emptyList()))
    }

    @Test
    fun `a table whose first row is blank falls through to the real header`() {
        val table = listOf(listOf("", "", ""), ukrsibbankHeader)
        assertEquals(BankCode.UKRSIBBANK, BankDetector.detect(table))
    }

    @Test
    fun `a row of empty cells is not treated as a header`() {
        assertNull(BankDetector.detect(listOf(listOf("", "", ""), listOf("", "", ""))))
    }

    @Test
    fun `the two supported spreadsheet exports are not confused with each other`() {
        val privatbank = listOf(
            listOf("Історія операцій за період 27.08.2026 - 27.09.2026"),
            listOf("Дата", "Картка", "Опис операції", "Валюта транзакції")
        )
        assertNotEquals(BankCode.UKRSIBBANK, BankDetector.detect(privatbank))
        assertNotEquals(BankCode.PRIVATBANK, BankDetector.detect(listOf(ukrsibbankHeader)))
    }
}
