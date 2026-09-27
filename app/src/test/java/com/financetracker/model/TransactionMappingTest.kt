package com.financetracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionMappingTest {

    private val entity = TransactionEntity(
        id = 7,
        userId = "uid-1",
        title = "TORUS",
        amount = 12.5,
        type = TransactionType.EXPENSE,
        category = "grocery",
        timestamp = 1_700_000_000_000,
        note = "coffee",
        externalId = "uk_import_abc123",
        source = "uk",
        currencyCode = "UAH",
        bankCode = "uk",
        cardLabel = "535129****5783"
    )

    @Test
    fun `provenance survives the trip to the domain`() {
        val domain = entity.toDomain()
        assertEquals("uk", domain.bankCode)
        assertEquals("535129****5783", domain.cardLabel)
        assertEquals("uk", domain.source)
        assertEquals("uk_import_abc123", domain.externalId)
    }

    @Test
    fun `provenance survives the trip back to storage`() {
        val stored = TransactionEntity.fromDomain(entity.toDomain(), "uid-1")
        // searchText is derived on the way in, so it is compared separately below.
        assertEquals(entity.copy(searchText = null), stored.copy(searchText = null))
    }

    @Test
    fun `a hand entered row has no bank and no external id`() {
        val manual = Transaction(
            title = "Lunch",
            amount = 5.0,
            type = TransactionType.EXPENSE,
            category = "food",
            timestamp = 0
        )
        val stored = TransactionEntity.fromDomain(manual, "uid-1")
        assertNull(stored.bankCode)
        assertNull(stored.externalId)
        assertNull(stored.source)
        assertNull(stored.cardLabel)
    }

    @Test
    fun `search text is derived on the way in so no writer can forget it`() {
        val manual = Transaction(
            title = "МОБІЛЬНИЙ ОПЕРАТОР",
            amount = 5.0,
            type = TransactionType.EXPENSE,
            category = "other",
            timestamp = 0,
            bankCode = "uk"
        )
        val stored = TransactionEntity.fromDomain(manual, "uid-1")
        assertTrue(stored.searchText!!.contains("мобільний"))
        assertTrue(stored.searchText!!.contains("ukrsibbank"))
    }

    @Test
    fun `the domain type keeps a defaulted constructor so existing call sites compile`() {
        val minimal = Transaction(
            title = "x",
            amount = 1.0,
            type = TransactionType.INCOME,
            category = "other",
            timestamp = 0
        )
        assertNull(minimal.bankCode)
        assertNull(minimal.cardLabel)
        assertNull(minimal.source)
        assertNull(minimal.externalId)
    }
}
