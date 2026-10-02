package com.financetracker.model

import kotlin.math.abs

/**
 * One transfer, as the two rows the bank sent for it.
 *
 * [inbound] is the leg that was credited and [outbound] the leg that was debited. Named
 * rather than left as an ordered [Pair] because which is which is the whole content of the
 * match, and a positional accessor would let a caller read them the wrong way round.
 */
data class TransferPair(
    val inbound: TransactionEntity,
    val outbound: TransactionEntity
)

/**
 * Recognising money moving between the user's own accounts out of the two rows a bank
 * reports for it.
 *
 * A bank cannot tell this app that a credit on one card and a debit on another are one
 * event, so it sends two, and each arrives looking exactly like an income and an expense.
 * Left alone they inflate both figures the dashboard is read for, and where the two accounts
 * hold different currencies they do not cancel in the balance either — the balance nets
 * income against expense, and 20 000 hryvnias is not 480 dollars.
 *
 * The match is deliberately narrow. Four things have to hold at once: the same amount, the
 * same currency, two different accounts, and a short distance in time. Each of those is
 * something a real transfer satisfies and an ordinary month of spending usually does not.
 *
 * **What this cannot see.** A payment received on one card and a payment of the identical
 * sum made from another inside [WINDOW_MILLIS] is indistinguishable from a transfer, and
 * will be relabelled. Nothing here invents a way round that — the honest options are to
 * leave the row alone, which preserves two wrong figures, or to relabel it, which is
 * recoverable. It is relabelled because the type is editable by hand afterwards and a
 * mislabelled row is a thing the user can see and change, whereas an inflated income is
 * not something anybody thinks to question.
 *
 * **What it never does.** It does not pair across currencies: reconciling those needs a rate
 * the app would have to supply, and a total is only ever produced by converting through a
 * rate it holds and can name. It does not touch hand-entered rows, which have no bank id to
 * match on and were typed deliberately. It does not touch a row that is already a transfer,
 * which is how a bond purchase is recorded.
 *
 * Pure and side-effect free: the caller decides what to persist, and what to do about the
 * rows that this claims for one run and not the next.
 */
object TransferPairing {

    /**
     * How far apart two legs may post and still be one transfer.
     *
     * Long enough for a bank to post one leg late — the debit usually clears first and the
     * credit follows — and short enough that a salary on one day and a bill the next are
     * not read as one movement. Judgement, not a fact about any particular bank, and named
     * so it is one edit if a real statement ever disagrees.
     */
    const val WINDOW_MILLIS = 10 * 60 * 1000L

    /**
     * How far apart the two legs of a transfer may post when they come from two statements
     * rather than one provider.
     *
     * A bank sync reports both legs in the same response, so [WINDOW_MILLIS] is the right
     * measure there. A statement is the other case: the sending bank debits and the
     * receiving bank credits after the overnight clearing, and the two lines land in
     * different files a user imports at different times. Measured across a real import the
     * debit preceded the credit by 500 to 1326 minutes, so a ten-minute window finds none
     * of them. A day covers that range with margin on both sides, and the observed maximum
     * is well inside it — three days adds no further pairs, so this is a plateau rather
     * than a threshold tuned to one dataset.
     *
     * The cost is real and is accepted deliberately: within a day, an equal amount in and
     * an equal amount out is likelier to coincide by chance than it is over ten minutes.
     * A wrongly paired row is one the user can see and re-type, which is the same trade
     * [pairs] already makes at the narrower window and for the same reason.
     */
    const val STATEMENT_WINDOW_MILLIS = 24 * 60 * 60 * 1000L

    /**
     * The transfers visible among [rows], at the default window.
     *
     * See [pairsWithin] for the windowed form.
     */
    fun pairs(rows: List<TransactionEntity>): List<TransferPair> =
        pairsWithin(rows, WINDOW_MILLIS)

    /**
     * The transfers visible among [rows], one per movement, nearest partner first, where two
     * legs may be [windowMillis] apart.
     *
     * Each row is used by at most one pair. Arrivals are taken in time order and each takes
     * the closest unclaimed departure that satisfies every rule, so two equal amounts in the
     * same window become two transfers rather than four.
     */
    fun pairsWithin(rows: List<TransactionEntity>, windowMillis: Long): List<TransferPair> {
        val arrivals = rows.filter { it.type == TransactionType.INCOME && eligible(it) }
            .sortedBy { it.timestamp }
        val departures = rows.filter { it.type == TransactionType.EXPENSE && eligible(it) }
            .sortedBy { it.timestamp }

        val claimed = mutableSetOf<Long>()
        val result = mutableListOf<TransferPair>()

        for (arrival in arrivals) {
            val departure = departures
                .filter { it.id !in claimed && matches(it, arrival, windowMillis) }
                .minByOrNull { abs(it.timestamp - arrival.timestamp) }
                ?: continue
            claimed += departure.id
            result += TransferPair(arrival, departure)
        }
        return result
    }

    /** Whether this row takes part at all, whatever it might match. */
    private fun eligible(row: TransactionEntity): Boolean =
        // A bank id is what says the row came from a statement rather than from the user's
        // own fingers, and is the only thing that promises a counterpart exists somewhere.
        row.externalId != null &&
            // Two rows that both decline to name a currency are not known to share one.
            row.currencyCode != null

    /** Every rule a departure must satisfy to be the other leg of [arrival]. */
    private fun matches(
        departure: TransactionEntity,
        arrival: TransactionEntity,
        windowMillis: Long
    ): Boolean =
        departure.amount == arrival.amount &&
            departure.currencyCode == arrival.currencyCode &&
            // The strongest signal available: money that left one account and arrived at
            // another. Equal keys mean one account, where the two rows are a receipt and a
            // payment and pairing them would be inventing a transfer out of two real ones.
            // Blank labels compare equal, so an account the bank did not name never pairs.
            accountKey(departure) != accountKey(arrival) &&
            abs(departure.timestamp - arrival.timestamp) <= windowMillis

    /**
     * Which account a row belongs to.
     *
     * The card within the bank, because two cards of one bank are two accounts and the card
     * is what distinguishes them; a bank on its own is not enough, and neither is a card
     * label that two banks might share.
     */
    private fun accountKey(row: TransactionEntity): String = "${row.bankCode}/${row.cardLabel}"
}