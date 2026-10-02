package com.financetracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recognising a transfer the bank reported as two separate movements.
 *
 * A transfer between the user's own accounts is one event that arrives as two rows: a credit
 * on the account it landed on and a debit on the one it left. Left as income and expense they
 * inflate both headline figures — the two numbers the dashboard is read for — and, where the
 * two accounts hold different currencies, they do not even cancel in the balance.
 *
 * These are worked from what a bank actually sends, so the discriminating rules are all
 * checked against a case that would otherwise be paired wrongly.
 */
class TransferPairingTest {

    private val at = 1_757_000_000_000L
    private fun minutes(n: Int) = at + n * 60_000L

    private var nextId = 1L

    private fun row(
        type: TransactionType,
        amount: Double = 20_000.0,
        currency: String? = "UAH",
        bank: String? = "UA_MBO",
        card: String? = "535129****5783",
        timestamp: Long = at,
        externalId: String? = "monobank_${nextId++}",
        direction: TransferDirection? = null
    ) = TransactionEntity(
        id = nextId++,
        userId = "uid-1",
        title = "TRANSFER",
        amount = amount,
        type = type,
        category = "transfer",
        timestamp = timestamp,
        externalId = externalId,
        source = externalId?.substringBefore('_'),
        currencyCode = currency,
        bankCode = bank,
        cardLabel = card,
        transferDirection = direction
    )

    private fun inbound() = row(TransactionType.INCOME, card = "537541****1234")
    private fun outbound() = row(TransactionType.EXPENSE)

    // MARK: - The two legs

    @Test
    fun `a credit and a debit of the same money on two own accounts are one transfer`() {
        val arriving = inbound()
        val leaving = outbound()

        val pair = TransferPairing.pairs(listOf(arriving, leaving)).single()

        assertEquals(arriving.id, pair.inbound.id)
        assertEquals(leaving.id, pair.outbound.id)
    }

    @Test
    fun `the order rows arrive in does not decide which leg is which`() {
        // Newest first is how the sync reads them, and the debit is very often the older of
        // the two. A pairing that took the first row as the arrival would invert half of
        // every transfer it matched.
        val leaving = outbound()
        val arriving = inbound()

        val pair = TransferPairing.pairs(listOf(leaving, arriving)).single()

        assertEquals(arriving.id, pair.inbound.id)
        assertEquals(leaving.id, pair.outbound.id)
    }

    @Test
    fun `the arriving leg is the one the bank credited`() {
        val pair = TransferPairing.pairs(listOf(inbound(), outbound())).single()

        assertEquals(TransactionType.INCOME, pair.inbound.type)
        assertEquals(TransactionType.EXPENSE, pair.outbound.type)
    }

    // MARK: - What must not be paired

    @Test
    fun `the same amount moving on one account is not a transfer`() {
        // Getting paid 20000 and spending 20000 from the same card is an ordinary month. The
        // single strongest signal that money crossed between two accounts is that it left
        // one and arrived at another, so an unshared account rules the pair out on its own.
        val pairs = TransferPairing.pairs(
            listOf(row(TransactionType.INCOME), row(TransactionType.EXPENSE))
        )

        assertTrue(pairs.isEmpty())
    }

    @Test
    fun `two movements too far apart in time are not one transfer`() {
        // A salary on the 1st and a rent payment on the 2nd are both real and both large.
        // Time is what separates them, so the window is what carries the weight here.
        val pairs = TransferPairing.pairs(
            listOf(
                row(TransactionType.INCOME, card = "537541****1234"),
                row(TransactionType.EXPENSE, timestamp = minutes(180))
            )
        )

        assertTrue(pairs.isEmpty())
    }

    @Test
    fun `movements in different currencies are not paired`() {
        // A currency swap leaves two accounts holding different currencies with two
        // different amounts. Calling that one transfer needs a rate to reconcile them, and
        // inventing one here would put a converted figure into a ledger that otherwise
        // holds only what the bank said. Left as two rows, and visible as such.
        val pairs = TransferPairing.pairs(
            listOf(
                row(TransactionType.INCOME, amount = 20_000.0, currency = "UAH", card = "537541****1234"),
                row(TransactionType.EXPENSE, amount = 480.0, currency = "USD")
            )
        )

        assertTrue(pairs.isEmpty())
    }

    @Test
    fun `rows of unknown currency are never matched against each other`() {
        // Two rows that both say "some currency" are not known to be the same currency, so
        // equal amounts are a coincidence rather than a fact.
        val pairs = TransferPairing.pairs(
            listOf(
                row(TransactionType.INCOME, currency = null, card = "537541****1234"),
                row(TransactionType.EXPENSE, currency = null)
            )
        )

        assertTrue(pairs.isEmpty())
    }

    @Test
    fun `a hand-entered row is never rewritten by a bank pairing`() {
        // The user typed this one deliberately. Rows the bank sent are the only ones whose
        // counterpart can be looked for; anything typed by hand has no id to match on and
        // no second leg the sync brought along.
        val typed = row(TransactionType.INCOME, card = "537541****1234", externalId = null)
        val imported = outbound()

        assertTrue(TransferPairing.pairs(listOf(typed, imported)).isEmpty())
    }

    @Test
    fun `a row that is already a transfer is not matched again`() {
        // A bond trade writes a TRANSFER row. Re-pairing it against a plain debit of the
        // same amount would relabel a real bond purchase as a move between own cards.
        val bondTrade = row(
            type = TransactionType.TRANSFER,
            card = "537541****1234",
            direction = TransferDirection.IN
        )
        val spending = outbound()

        assertTrue(TransferPairing.pairs(listOf(bondTrade, spending)).isEmpty())
    }

    // MARK: - Two windows, one per source of the rows

    @Test
    fun `a wider window pairs legs an overnight gap apart`() {
        val arriving = inbound().copy(timestamp = minutes(14 * 60))
        val leaving = outbound()

        val pair = TransferPairing
            .pairsWithin(listOf(arriving, leaving), TransferPairing.STATEMENT_WINDOW_MILLIS)
            .single()

        assertEquals(arriving.id, pair.inbound.id)
        assertEquals(leaving.id, pair.outbound.id)
    }

    @Test
    fun `the wider window still refuses legs more than a day apart`() {
        val arriving = inbound().copy(timestamp = minutes(25 * 60))
        val leaving = outbound()

        val pairs = TransferPairing
            .pairsWithin(listOf(arriving, leaving), TransferPairing.STATEMENT_WINDOW_MILLIS)

        assertTrue(pairs.isEmpty())
    }

    @Test
    fun `the sync window is unchanged and still refuses an overnight gap`() {
        // The narrow window is the sync path's, where both legs arrive in one response.
        // Widening it there would let an ordinary day of spending pair itself.
        val arriving = inbound().copy(timestamp = minutes(14 * 60))
        val leaving = outbound()

        assertTrue(TransferPairing.pairs(listOf(arriving, leaving)).isEmpty())
    }

    // MARK: - Claiming

    @Test
    fun `a movement is used by at most one pair`() {
        // Two 500 transfers inside one window is four rows on four different accounts and two
        // transfers. Matching in both directions would pair each debit with both credits and
        // report four, and then one row would be a leg of two transfers at once.
        val arrivingA = row(TransactionType.INCOME, amount = 500.0, card = "card-a")
        val arrivingB = row(TransactionType.INCOME, amount = 500.0, card = "card-b")
        val leavingA = row(TransactionType.EXPENSE, amount = 500.0, card = "card-c")
        val leavingB = row(TransactionType.EXPENSE, amount = 500.0, card = "card-d")

        val pairs = TransferPairing.pairs(listOf(arrivingA, arrivingB, leavingA, leavingB))

        assertEquals(2, pairs.size)
        assertEquals(4, pairs.flatMap { listOf(it.inbound.id, it.outbound.id) }.distinct().size)
    }

    @Test
    fun `the nearest of two candidate departures is the one taken`() {
        // One credit, two debits for the same sum inside the window. Taking whichever came
        // first in the list would attach the transfer to the wrong leg of it and leave the
        // other debit looking like spending.
        val arriving = row(TransactionType.INCOME, amount = 500.0, card = "card-a", timestamp = at)
        val nearer = row(TransactionType.EXPENSE, amount = 500.0, card = "card-b", timestamp = minutes(-1))
        val further = row(TransactionType.EXPENSE, amount = 500.0, card = "card-c", timestamp = minutes(4))

        val pair = TransferPairing.pairs(listOf(arriving, nearer, further)).single()

        assertEquals(nearer.id, pair.outbound.id)
    }

    // MARK: - What pairing costs

    @Test
    fun `a genuine receipt and a genuine payment of the same sum are paired`() {
        // The cost of the heuristic, pinned as a test so it cannot be forgotten: being paid
        // 20000 on one card and spending 20000 from another inside the window is a
        // coincidence this cannot see. The row is kept and the type is editable by hand —
        // mislabelling is recoverable, an inflated income figure is not something the user
        // would think to question.
        val pairs = TransferPairing.pairs(
            listOf(
                row(TransactionType.INCOME, card = "537541****1234"),
                row(TransactionType.EXPENSE)
            )
        )

        assertEquals(1, pairs.size)
    }

    @Test
    fun `nothing to pair is not an error`() {
        assertTrue(TransferPairing.pairs(emptyList()).isEmpty())
        assertTrue(TransferPairing.pairs(listOf(inbound())).isEmpty())
    }
}