package com.financetracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.financetracker.model.BankRef
import com.financetracker.model.Bond
import com.financetracker.model.BondTradeSide
import com.financetracker.model.TransactionType
import com.financetracker.model.TransferDirection
import com.financetracker.repository.BondRepository
import com.financetracker.repository.RecordTradeResult
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [BondRepository] against a real SQLite database.
 *
 * The point of every test here is the pairing. A bond trade and the cash row that moved with
 * it are two writes, and a mock would happily pass a repository that wrote only one of them
 * — which is the failure this whole class exists to rule out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BondRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: BondRepository

    private val bond = Bond(
        isin = "UA9000012345",
        name = "ОвДП 24/Б",
        nominal = 1000.0,
        nominalCurrency = "UAH",
        couponPercent = 9.5
    )

    private val bank = BankRef("mo", "Monobank")

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = BondRepository(db, db.bondDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun buy(
        quantity: Int = 1,
        price: Double = 995.0,
        currency: String = "UAH",
        amount: Double? = null,
        commission: Double = 0.0,
        accrued: Double = 0.0,
        date: Long = 1_700_000_000_000,
        userId: String = "uid-1"
    ) = repo.recordTrade(
        userId = userId,
        bond = bond,
        side = BondTradeSide.BUY,
        quantity = quantity,
        price = price,
        accruedInterest = accrued,
        commission = commission,
        tradeDate = date,
        bank = bank,
        settlementCurrency = currency,
        settlementAmount = amount
    )

    private suspend fun sell(
        quantity: Int,
        price: Double = 1010.0,
        userId: String = "uid-1"
    ) = repo.recordTrade(
        userId = userId,
        bond = bond,
        side = BondTradeSide.SELL,
        quantity = quantity,
        price = price,
        accruedInterest = 0.0,
        commission = 0.0,
        tradeDate = 1_700_100_000_000,
        bank = bank,
        settlementCurrency = "UAH",
        settlementAmount = null
    )

    private suspend fun cashRows() = db.transactionDao().getAllForUser("uid-1").first()

    // MARK: - The pairing

    @Test
    fun `a purchase writes both the trade and the cash row`() = runTest {
        val result = buy(quantity = 2)
        assertTrue(result is RecordTradeResult.Recorded)

        assertEquals(1, db.bondDao().getTrades().size)
        val cash = cashRows()
        assertEquals(1, cash.size)
        // 2 x 995 = 1990.
        assertEquals(1990.0, cash.single().amount, 0.0)
    }

    @Test
    fun `the two rows point at each other`() = runTest {
        // Without this the rows are merely both present, and a delete of either would leave
        // an orphan that looks valid until someone reconciles it by hand.
        val result = buy() as RecordTradeResult.Recorded

        val trade = db.bondDao().getTrades().single()
        val cash = cashRows().single()
        assertEquals(result.transactionId, trade.transactionId)
        assertEquals(cash.id, trade.transactionId)
    }

    @Test
    fun `a purchase is recorded as a transfer out, not as an expense`() = runTest {
        buy()

        val cash = cashRows().single()
        assertEquals(TransactionType.TRANSFER, cash.type)
        assertEquals(TransferDirection.OUT, cash.transferDirection)
    }

    @Test
    fun `a sale is recorded as a transfer in`() = runTest {
        buy(quantity = 3)
        sell(quantity = 1)

        val cash = cashRows().single { it.transferDirection == TransferDirection.IN }
        assertEquals(TransactionType.TRANSFER, cash.type)
        // 1010.00 per bond, entered as money.
        assertEquals(1010.0, cash.amount, 0.0)
    }

    @Test
    fun `the cash row lands in the currency the account holds`() = runTest {
        // A purchase settled in dollars has to be counted against the dollar balance, or the
        // cash figure contradicts the one the user saw on their statement.
        buy(quantity = 1, price = 995.0, currency = "USD", amount = 325.0)

        val cash = cashRows().single()
        assertEquals("USD", cash.currencyCode)
        assertEquals(325.0, cash.amount, 0.0)
    }

    @Test
    fun `the cash row is findable by the bond's name`() = runTest {
        buy()

        // searchText is baked in at write time; a row written without one is permanently
        // unfindable by the instrument the user is looking for.
        val haystack = cashRows().single().searchText!!
        assertTrue(haystack.contains("овдп"))
        assertTrue(haystack.contains("monobank"))
    }

    @Test
    fun `the note records the price as money, not as a percentage`() = runTest {
        // The stored and displayed unit is money, so the note has to print money too. A note
        // reading "99.5%" would send the user back to a percentage they no longer type.
        buy(quantity = 3, price = 995.0)

        val purchased = cashRows().single()
        assertTrue(
            "Купівля 3 шт.".startsWith(
                purchased.note!!.substringBefore("@").trim()
            )
        )
        // 995.00 formatted with the device's separators and the ₴ symbol.
        assertTrue(purchased.note!!.contains("995"))
        assertTrue(purchased.note!!.contains("₴") || purchased.note!!.contains("UAH"))
        assertTrue(!purchased.note!!.contains("%"))
    }

    @Test
    fun `the title is the instrument, and the note says which way the trade went`() = runTest {
        // A title of "Купівля ОвДП" reads as a sentence about a purchase, and a list of them
        // is a list of sentences. The instrument is what the user is looking for; the side is
        // a property of the trade, and transfers are drawn neutrally, so the note is the only
        // thing that keeps a round trip's two rows apart.
        buy(quantity = 3, price = 995.0)

        val purchased = cashRows().single()
        assertEquals("ОвДП 24/Б", purchased.title)
        assertTrue(purchased.note!!.startsWith("Купівля 3 шт. @"))

        sell(quantity = 1, price = 1010.0)
        val rows = cashRows().sortedBy { it.id }
        assertEquals(listOf("ОвДП 24/Б", "ОвДП 24/Б"), rows.map { it.title })
        assertTrue(rows[0].note!!.startsWith("Купівля 3 шт. @"))
        assertTrue(rows[1].note!!.startsWith("Продаж 1 шт. @"))
    }

    @Test
    fun `the trade date is used as the transaction date, not the time of entry`() = runTest {
        // A trade entered on Monday for Friday's settlement belongs to Friday, or the whole
        // monthly grouping is wrong.
        val friday = 1_700_000_000_000L
        buy(date = friday)

        assertEquals(friday, cashRows().single().timestamp)
    }

    // MARK: - Bonds

    @Test
    fun `a new instrument is stored once, on its first trade`() = runTest {
        buy(quantity = 1)
        buy(quantity = 1, price = 1000.0)

        assertEquals(1, db.bondDao().getBondsOnce().size)
        assertEquals(2, db.bondDao().getTrades().size)
    }

    @Test
    fun `a lower case isin is stored upper cased`() = runTest {
        // Typed on a phone keyboard. Left as typed, the same instrument becomes two bonds
        // with one trade each and the position reads as a single bond held twice over.
        repo.recordTrade(
            userId = "uid-1",
            bond = bond.copy(isin = " ua9000012345 "),
            side = BondTradeSide.BUY,
            quantity = 1,
            price = 995.0,
            accruedInterest = 0.0,
            commission = 0.0,
            tradeDate = 1_700_000_000_000,
            bank = bank,
            settlementCurrency = "UAH",
            settlementAmount = null
        )

        assertEquals(listOf("UA9000012345"), db.bondDao().getBondsOnce().map { it.isin })
    }

    @Test
    fun `a bond with blank isin falls back to its name as the instrument identifier`() = runTest {
        val result = repo.recordTrade(
            userId = "uid-1",
            bond = bond.copy(isin = "", name = "Військові ОвДП"),
            side = BondTradeSide.BUY,
            quantity = 2,
            price = 1000.0,
            accruedInterest = 0.0,
            commission = 0.0,
            tradeDate = 1_700_000_000_000,
            bank = bank,
            settlementCurrency = "UAH",
            settlementAmount = null
        )

        assertTrue(result is RecordTradeResult.Recorded)
        val bonds = db.bondDao().getBondsOnce()
        assertEquals(1, bonds.size)
        assertEquals("ВІЙСЬКОВІ ОВДП", bonds.single().isin)
        assertEquals("Військові ОвДП", bonds.single().name)
    }

    @Test
    fun `a dollar bond keeps its denomination on the instrument and the trade`() = runTest {
        // ОВДП are not all UAH-nominal. A dollar bond's price, accrual and commission are
        // dollar figures, and losing the denomination at the bond row would mean the money
        // numbers are silently read as hryvnia everywhere they are summed or formatted.
        repo.recordTrade(
            userId = "uid-1",
            bond = bond.copy(isin = "UA9000055555", nominalCurrency = "USD"),
            side = BondTradeSide.BUY,
            quantity = 1,
            price = 995.0,
            accruedInterest = 3.0,
            commission = 2.0,
            tradeDate = 1_700_000_000_000,
            bank = bank,
            settlementCurrency = "USD",
            settlementAmount = null
        )

        val stored = db.bondDao().getByIsin("UA9000055555")!!
        assertEquals("USD", stored.nominalCurrency)
        assertEquals(995.0, db.bondDao().getTrades().single().price, 0.0)
    }

    @Test
    fun `a dollar bond settled in hryvnia needs no implied rate of itself`() = runTest {
        // A settlement in the bond's own currency carries no rate. In UAH that means a UAH
        // bond; the point of the comparison being against the bond's denomination rather than
        // a hard-coded UAH is that it must hold for a dollar bond too.
        repo.recordTrade(
            userId = "uid-1",
            bond = bond.copy(isin = "UA9000055555", nominalCurrency = "USD"),
            side = BondTradeSide.BUY,
            quantity = 1,
            price = 995.0,
            accruedInterest = 0.0,
            commission = 0.0,
            tradeDate = 1_700_000_000_000,
            bank = bank,
            settlementCurrency = "USD",
            settlementAmount = null
        )

        // No amount charged field, because the settlement currency is the bond's own.
        val cash = cashRows().single()
        assertEquals(995.0, cash.amount, 0.0)
        assertEquals("USD", cash.currencyCode)
    }

    @Test
    fun `a later trade does not overwrite the stored terms`() = runTest {
        buy()
        // The form still holds a coupon the user has since edited elsewhere. Restating it
        // here would silently change what every position on this bond reports.
        buy(quantity = 1, price = 1000.0)

        assertEquals(9.5, db.bondDao().getBondsOnce().single().couponPercent!!, 0.0)
    }

    @Test
    fun `an unknown isin is found without regard to case`() = runTest {
        buy()

        assertEquals("UA9000012345", repo.findBond("ua9000012345")!!.isin)
        assertNull(repo.findBond("UA9000099999"))
    }

    @Test
    fun `the held quantity is what has been bought and not sold`() = runTest {
        assertEquals(0, repo.heldQuantity(bond.isin))
        buy(quantity = 5)
        assertEquals(5, repo.heldQuantity(bond.isin))
        sell(quantity = 2)
        assertEquals(3, repo.heldQuantity(bond.isin))
    }

    // MARK: - Overselling

    @Test
    fun `selling more than is held is refused, and writes nothing`() = runTest {
        buy(quantity = 2)

        val result = sell(quantity = 5)

        assertEquals(RecordTradeResult.Oversell(2), result)
        // The refusal is the whole point: no trade, and no cash row claiming money arrived.
        assertEquals(1, db.bondDao().getTrades().size)
        assertEquals(1, cashRows().size)
    }

    @Test
    fun `selling exactly the holding is allowed`() = runTest {
        buy(quantity = 2)

        assertTrue(sell(quantity = 2) is RecordTradeResult.Recorded)
        assertEquals(0, repo.heldQuantity(bond.isin))
    }

    @Test
    fun `a sale of an instrument never bought is refused`() = runTest {
        val result = sell(quantity = 1)

        assertEquals(RecordTradeResult.Oversell(0), result)
        assertEquals(0, cashRows().size)
    }

    @Test
    fun `a refused sale of an unknown instrument does not create the instrument`() = runTest {
        // The instrument insert has to come after the holding check, not before it. Inserted
        // first, a sale of something never bought would leave the bond behind: an instrument
        // in the list that holds nothing, was never bought, and cannot be explained by any
        // trade — while every assertion about trades and cash still passed.
        sell(quantity = 1)

        assertTrue(db.bondDao().getBondsOnce().isEmpty())
    }

    @Test
    fun `an oversell is refused even when the form thought the bonds were there`() = runTest {
        // The form's holding count is a snapshot. Two screens open, both showing 5, both
        // selling 4: the second must not be believed.
        buy(quantity = 5)
        sell(quantity = 4)

        val second = sell(quantity = 4)

        assertEquals(RecordTradeResult.Oversell(1), second)
        assertEquals(1, repo.heldQuantity(bond.isin))
    }

    // MARK: - Validation

    @Test
    fun `a zero quantity is refused before anything is written`() = runTest {
        val result = buy(quantity = 0)

        assertTrue(result is RecordTradeResult.Invalid)
        assertEquals(0, db.bondDao().getTrades().size)
        assertEquals(0, cashRows().size)
    }

    @Test
    fun `a zero price is refused`() = runTest {
        assertTrue(buy(price = 0.0) is RecordTradeResult.Invalid)
        assertEquals(0, cashRows().size)
    }

    // MARK: - Positions

    @Test
    fun `positions fold from the trades`() = runTest {
        buy(quantity = 3, price = 995.0)

        val position = repo.observePositions().first().single()
        assertEquals(3, position.quantity)
        // 3 x 995.00 in the bond's own currency.
        assertEquals(2985.0, position.cost, 0.0)
    }

    @Test
    fun `trades are read newest first for the list, and that is a display order only`() = runTest {
        buy(quantity = 1, price = 950.0, date = 1_700_000_000_000)
        buy(quantity = 1, price = 1000.0, date = 1_700_200_000_000)

        val trades = repo.observeTrades().first()
        assertEquals(listOf(1000.0, 950.0), trades.map { it.price })
        // The position itself is order-independent, and this is the assertion that says so.
        assertEquals(1950.0, repo.observePositions().first().single().cost, 0.0)
    }

    @Test
    fun `a bond with no trades has no position`() = runTest {
        assertTrue(repo.observePositions().first().isEmpty())
    }

    @Test
    fun `a second trade on the same bond pushes a new position out`() = runBlocking {
        // Bond rows are written once and never change, so a positions flow built on the bond
        // table alone emits on the first trade and then goes quiet. Every later purchase would
        // be recorded and never shown, which is the whole point of the feature.
        //
        // runBlocking rather than runTest: Room runs the query on its own executor, and the
        // emissions come back on the collector's dispatcher. A virtual-time collector is never
        // resumed by a real thread, so under runTest this would collect nothing and pass for
        // the wrong reason.
        val seen = CopyOnWriteArrayList<Int>()
        val job = launch(Dispatchers.Default) {
            repo.observePositions().collect { seen += it.singleOrNull()?.quantity ?: 0 }
        }
        awaitQuantity(seen, 0)

        buy(quantity = 2)
        awaitQuantity(seen, 2)

        buy(quantity = 3)
        awaitQuantity(seen, 5)

        job.cancel()
    }

    /** Waits for the collector to have seen a position of [quantity], and fails if it never does. */
    private suspend fun awaitQuantity(seen: List<Int>, quantity: Int) {
        withTimeout(5_000) {
            while (quantity !in seen) delay(10)
        }
    }

    // MARK: - Deleting the cash row

    @Test
    fun `deleting the cash row of a trade deletes the trade with it`() = runTest {
        val result = buy(quantity = 2) as RecordTradeResult.Recorded

        db.transactionDao().delete(result.transactionId)

        assertEquals(0, db.bondDao().getTrades().size)
        assertEquals(0, repo.heldQuantity(bond.isin))
    }

    // MARK: - Users

    @Test
    fun `one user's trades do not appear in another's cash rows`() = runTest {
        // The bonds are app-wide, the cash rows are not. Without the userId filter on the
        // query a shared device would show one person's trading to another.
        buy(userId = "uid-1")
        repo.recordTrade(
            userId = "uid-2",
            bond = bond,
            side = BondTradeSide.BUY,
            quantity = 1,
            price = 995.0,
            accruedInterest = 0.0,
            commission = 0.0,
            tradeDate = 1_700_000_000_000,
            bank = bank,
            settlementCurrency = "UAH",
            settlementAmount = null
        )

        assertEquals(1, cashRows().size)
        assertEquals(1, db.transactionDao().getAllForUser("uid-2").first().size)
    }

    @Test
    fun `a purchase with no bank is still recordable`() = runTest {
        // A user who has not set up banks, or who bought over the phone, must not be blocked.
        val result = repo.recordTrade(
            userId = "uid-1",
            bond = bond,
            side = BondTradeSide.BUY,
            quantity = 1,
            price = 995.0,
            accruedInterest = 0.0,
            commission = 0.0,
            tradeDate = 1_700_000_000_000,
            bank = null,
            settlementCurrency = "UAH",
            settlementAmount = null
        )

        assertTrue(result is RecordTradeResult.Recorded)
        assertNull(cashRows().single().bankCode)
    }
}
