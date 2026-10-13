package com.financetracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val stored = TransactionEntity.fromDomain(entity.toDomain(), "uid-1", bank = null)
        // searchText is derived on the way in, so it is compared separately below.
        // The type flag is *carried*, not stamped: this factory no longer decides that a row
        // written through it has a person's type. It reads the flag off the domain object, so a
        // row that was imported (flag false) and is re-saved without a type change stays false and
        // remains eligible for transfer pairing. The form's add path is what sets it true.
        assertEquals(entity.copy(searchText = null), stored.copy(searchText = null))
    }

    @Test
    fun `a row round-tripped through the domain keeps its typeUserSet flag`() {
        // The flag is a property of the row, not an artifact of which factory wrote it. An
        // imported row (false) that a later edit round-trips must come back false, or the edit
        // would permanently opt it out of being paired with a counterpart that arrives later.
        val imported = entity.copy(typeUserSet = false)
        val storedImported = TransactionEntity.fromDomain(imported.toDomain(), "uid-1", bank = null)
        assertFalse(storedImported.typeUserSet)

        val corrected = entity.copy(typeUserSet = true)
        val storedCorrected = TransactionEntity.fromDomain(corrected.toDomain(), "uid-1", bank = null)
        assertTrue(storedCorrected.typeUserSet)
    }

    @Test
    fun `the form's write path marks a new row's type as user set`() {
        // The add path is the one place a type is chosen by a person rather than inferred, so it
        // is the one place the flag is set true. `fromDomain` honours it rather than forcing it,
        // which is what lets the same factory preserve an imported row's false on the way back.
        val manual = Transaction(
            title = "Lunch",
            amount = 5.0,
            type = TransactionType.EXPENSE,
            category = "food",
            timestamp = 0,
            typeUserSet = true
        )
        val stored = TransactionEntity.fromDomain(manual, "uid-1", bank = null)
        assertTrue(stored.typeUserSet)
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
        val stored = TransactionEntity.fromDomain(manual, "uid-1", bank = null)
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
            bankCode = BankCode.UKRSIBBANK
        )
        val stored = TransactionEntity.fromDomain(
            manual,
            "uid-1",
            bank = BankRef(BankCode.UKRSIBBANK, "Ukrsibbank")
        )
        assertTrue(stored.searchText!!.contains("мобільний"))
        assertTrue(stored.searchText!!.contains("ukrsibbank"))
    }

    @Test
    fun `a row written after a rename uses the new name`() {
        // The name is read at write time, so this is the half of the rule that does apply
        // to a new row. The other half — that a rename leaves rows already stored alone —
        // is a property of the database, so it is asserted in BankRepositoryTest.
        val stored = TransactionEntity.fromDomain(
            Transaction(
                title = "Coffee",
                amount = 5.0,
                type = TransactionType.EXPENSE,
                category = "food",
                timestamp = 0,
                bankCode = BankCode.MONOBANK
            ),
            "uid-1",
            bank = BankRef(BankCode.MONOBANK, "My Bank")
        )
        assertTrue(stored.searchText!!.contains("my bank"))
    }

    @Test
    fun `a null bank leaves the code out of the search text`() {
        // A row with no resolved name must not fall back to searching for the raw code, or
        // "uk" would match any text containing those two letters.
        val stored = TransactionEntity.fromDomain(
            Transaction(
                title = "Coffee",
                amount = 5.0,
                type = TransactionType.EXPENSE,
                category = "food",
                timestamp = 0,
                bankCode = BankCode.UKRSIBBANK
            ),
            "uid-1",
            bank = null
        )
        assertFalse(stored.searchText!!.contains(BankCode.UKRSIBBANK))
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
