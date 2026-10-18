package com.financetracker.repository

import androidx.room.withTransaction
import com.financetracker.data.AppDatabase
import com.financetracker.data.BondDao
import com.financetracker.data.backup.BackupReason
import com.financetracker.data.backup.BackupRequests
import com.financetracker.model.Bank
import com.financetracker.model.BankNames
import com.financetracker.model.BankRef
import com.financetracker.model.Bond
import com.financetracker.model.BondEntity
import com.financetracker.model.BondMath
import com.financetracker.model.BondTradeProblem
import com.financetracker.model.BondPosition
import com.financetracker.model.BondTrade
import com.financetracker.model.BondTradeEntity
import com.financetracker.model.BondTradeSide
import com.financetracker.model.SearchText
import com.financetracker.model.TransactionEntity
import com.financetracker.model.TransactionType
import com.financetracker.model.TransferDirection
import com.financetracker.util.MoneyFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bonds, their trades, and the cash movement each trade implies.
 *
 * The pairing of a trade with a cash row is the reason this exists rather than a DAO call
 * from the form. A bond purchase is two facts — the bonds changed hands, and money left the
 * account — and recording only the first gives a position the balance knows nothing about,
 * while recording only the second gives a spend the user did not make. So both go in inside
 * one [AppDatabase.withTransaction]: a crash between them would leave a position the cash
 * balance contradicts, and there is no way to notice that afterwards.
 */
@Singleton
class BondRepository @Inject constructor(
    private val db: AppDatabase,
    private val bondDao: BondDao,
    private val backupRequests: BackupRequests
) {

    private val transactionDao = db.transactionDao()

    /**
     * Holdings for one account, folded from its trades, newest bond first.
     *
     * Both tables are observed, not just the bonds. Bonds are written once and never change
     * again, so watching only those would emit once when the first trade creates the row and
     * then never again — a second purchase of the same bond, which changes the position, would
     * not be noticed by anything watching this. The whole trade list is read here rather than
     * one query per bond: it is small, and it keeps the two reads of the same emit.
     *
     * Scoped to the account: the trade list a position is folded from is one account's, so a
     * second account's purchases do not show up as this account's holdings.
     */
    fun observePositions(userId: String): Flow<List<BondPosition>> =
        combine(bondDao.observeBonds(), bondDao.observeTrades(userId)) { bonds, trades ->
            val byIsin = trades.groupBy { it.isin }
            bonds.mapNotNull { bond ->
                // Reversed, because observeTrades answers newest-first and the fold is a
                // running total that must see the trades in the order they happened: a
                // redemption processed before the buy it redeems is clamped to nothing, and
                // the position then reports the redeemed bonds as still held. Reversing the
                // newest-first list yields oldest-first, ties broken by id ascending — the
                // same order getTrades answers in, which is the order the fold is built for.
                BondMath.position(
                    bond.toDomain(),
                    byIsin[bond.isin].orEmpty().asReversed().map { it.toDomain() }
                )
            }
        }

    fun observeTrades(userId: String): Flow<List<BondTrade>> =
        bondDao.observeTrades(userId).map { rows -> rows.map { it.toDomain() } }

    fun observeBonds(): Flow<List<Bond>> = bondDao.observeBonds().map { rows -> rows.map { it.toDomain() } }

    /**
     * The instrument for an ISIN, typed or pasted, or null for one this app has not seen.
     *
     * The caller falls back to asking the user for the terms. The ISIN is normalised the same
     * way on the way in as on the way out, or a lower-cased paste would always miss.
     */
    suspend fun findBond(rawIsin: String): Bond? =
        bondDao.getByIsin(BondMath.normaliseIsin(rawIsin))?.toDomain()

    /** What the form needs to show while the user is still typing a trade. */
    suspend fun heldQuantity(userId: String, isin: String): Int {
        val bond = bondDao.getByIsin(BondMath.normaliseIsin(isin)) ?: return 0
        return BondMath.position(bond.toDomain(), bondDao.getTradesFor(userId, bond.isin).map { it.toDomain() })
            ?.quantity
            ?: 0
    }

    /**
     * Whether an instrument has any trade at all, across every account.
     *
     * The edit form's currency field is locked on this. Not [heldQuantity]'s zero: a fully-sold
     * bond holds nothing but its trades' prices are still money in the bond's own currency, so
     * the denomination is just as fixed for it.
     */
    suspend fun hasTrades(isin: String): Boolean =
        bondDao.tradeCount(BondMath.normaliseIsin(isin)) > 0

    /**
     * Corrects a bond's descriptive terms, or reports that there was nothing to correct.
     *
     * The ISIN is not among them: it is the primary key and what every trade refers to, so a
     * corrected one is a different instrument and belongs in as a new row. This is the only
     * writer of a stored bond's terms — [recordTrade] re-reads them rather than restating them —
     * so an edit here is what every position, payout and dashboard card is folded against.
     *
     * Two refusals matter more than the write:
     *
     * - **Nothing changed is not a write.** The row is compared field by field first, and an
     *   identical form neither touches the DAO nor asks for a backup, because a write that
     *   changes nothing must not put an upload on the wire.
     * - **The denomination is fixed once a trade exists.** Every trade's price and НКД are money
     *   in the bond's currency, so changing it would silently re-read those figures against a
     *   different one. The whole edit is refused rather than half-applied, and the count is a
     *   count rather than the holding so a fully-sold bond is covered too.
     */
    suspend fun updateTerms(bond: Bond): UpdateTermsResult {
        val isin = BondMath.normaliseIsin(bond.isin)
        val stored = bondDao.getByIsin(isin) ?: return UpdateTermsResult.Unchanged

        if (stored.nominalCurrency != bond.nominalCurrency && bondDao.tradeCount(isin) > 0) {
            return UpdateTermsResult.CurrencyLocked
        }

        val updated = stored.copy(
            name = bond.name.trim(),
            nominal = bond.nominal,
            nominalCurrency = bond.nominalCurrency,
            couponPercent = bond.couponPercent,
            couponPeriodMonths = bond.couponPeriodMonths,
            maturityDate = bond.maturityDate
        )
        if (updated == stored) return UpdateTermsResult.Unchanged

        bondDao.update(updated)
        // Asked for outside any transaction, and only after a real change: the upload runs on
        // its own scope and could otherwise read the database before this commit landed.
        backupRequests.requestUpload(BackupReason.BOND)
        return UpdateTermsResult.Updated
    }

    /**
     * Records a trade and the cash that moved with it, or nothing at all.
     *
     * Every read and every write happens inside one database transaction, including the
     * holding check. That is not tidiness. The check is what stops a sale of bonds that were
     * never bought, and a check made before the transaction opens is a check that two sells
     * can both pass: each reads a holding of five, each writes a sale of five, and the
     * position ends up at minus five with a cash row for each. Inside the transaction the
     * second sell reads what the first one wrote.
     *
     * For the same reason the bond row is inserted inside it too. Inserted outside, a
     * refused sale of an instrument the app has never seen would still leave the instrument
     * behind, and a crash between the insert and the cash row would leave a bond nobody
     * bought. The insert is ignored on conflict, so an existing instrument's terms are never
     * overwritten by whatever the form happened to have in its fields — the user editing a
     * trade should not silently restate a coupon they were not looking at.
     */
    suspend fun recordTrade(
        userId: String,
        bond: Bond,
        side: BondTradeSide,
        quantity: Int,
        price: Double,
        accruedInterest: Double,
        commission: Double,
        tradeDate: Long,
        bank: BankRef?,
        settlementCurrency: String,
        settlementAmount: Double?
    ): RecordTradeResult {
        val isin = BondMath.normaliseIsin(bond.isin).ifBlank {
            BondMath.normaliseIsin(bond.name)
        }
        if (isin.isBlank()) {
            return RecordTradeResult.Invalid(BondTradeProblem.NAME_MISSING)
        }
        // Pure functions, so they stay outside: there is nothing to roll back. The price is
        // money in the bond's currency and is capped at twice the nominal, not checked
        // against a percentage ceiling — that is the whole point of storing money.
        BondMath.validateQuantity(quantity)?.let { return RecordTradeResult.Invalid(it) }
        BondMath.validatePrice(price, bond.nominal.takeIf { it > 0.0 })?.let { return RecordTradeResult.Invalid(it) }
        BondMath.validateNominal(bond.nominal)?.let { return RecordTradeResult.Invalid(it) }

        val result = db.withTransaction {
            val existing = bondDao.getByIsin(isin)
            // Checked against what is already held, before the insert, so a disposal of an
            // instrument the app has never seen is refused as the oversell it is rather than
            // creating the instrument in order to empty it. Written as "anything but a
            // purchase" so a redemption is covered by the same guard as a sale: both take
            // bonds out of the holding, and a redemption of a position that was already sold
            // would otherwise be accepted.
            if (side != BondTradeSide.BUY) {
                val held = existing?.let { BondMath.position(it.toDomain(), bondDao.getTradesFor(userId, isin).map { t -> t.toDomain() }) }
                    ?.quantity
                    ?: 0
                if (quantity > held) {
                    // Checked here and not left to the form, because the form's view of the
                    // holding is a snapshot and a second sell from another screen can land
                    // after it was drawn.
                    return@withTransaction RecordTradeResult.Oversell(held)
                }
            }

            // Terms are written only for an instrument the app has not seen. An existing bond
            // is re-read rather than updated, so a form that still holds a coupon the user
            // is editing does not restate it over the stored one.
            val stored = existing ?: run {
                bondDao.insertIfAbsent(bond.toEntity(isin))
                bondDao.getByIsin(isin)
            } ?: return@withTransaction RecordTradeResult.Invalid(BondTradeProblem.NOT_SAVED)

            val marketValue = BondMath.tradeTotal(
                BondTrade(
                    id = 0,
                    isin = isin,
                    side = side,
                    quantity = quantity,
                    price = price,
                    accruedInterest = accruedInterest,
                    commission = commission,
                    tradeDate = tradeDate,
                    bankCode = bank?.code,
                    transactionId = null
                )
            )
            val settlement = BondMath.settlement(
                marketValue = marketValue,
                bondCurrency = stored.nominalCurrency,
                settlementCurrency = settlementCurrency,
                actualAmount = settlementAmount
            )

            val note = tradeNote(side, quantity, price, bond.nominalCurrency)
            val cashId = transactionDao.insert(
                TransactionEntity(
                    userId = userId,
                    title = bondTitle(stored.name),
                    amount = settlement.amount,
                    type = TransactionType.TRANSFER,
                    category = CATEGORY,
                    timestamp = tradeDate,
                    note = note,
                    currencyCode = settlementCurrency,
                    bankCode = bank?.code,
                    // Derived here for the same reason as everywhere else: a row whose
                    // haystack is built at some other call site is a row that can be written
                    // without one.
                    searchText = SearchText.of(
                        bondTitle(stored.name),
                        note,
                        CATEGORY,
                        bank,
                        null
                    ),
                    transferDirection = when (side) {
                        BondTradeSide.BUY -> TransferDirection.OUT
                        // Money coming back: a sale's proceeds, or the nominal a redemption
                        // returns. Return of capital either way, so neither is income — the
                        // coupon that may arrive with a redemption is a separate row, because
                        // it is earned rather than given back.
                        BondTradeSide.SELL, BondTradeSide.REDEMPTION -> TransferDirection.IN
                    }
                )
            )

            val tradeId = bondDao.insertTrade(
                BondTradeEntity(
                    userId = userId,
                    isin = isin,
                    side = side,
                    quantity = quantity,
                    price = price,
                    accruedInterest = accruedInterest,
                    commission = commission,
                    tradeDate = tradeDate,
                    bankCode = bank?.code,
                    transactionId = cashId
                )
            )
            RecordTradeResult.Recorded(tradeId = tradeId, transactionId = cashId)
        }

        // Asked for outside the transaction, never inside it. The upload runs on its own scope
        // and could otherwise read the database before this commit landed, writing a backup
        // that is missing the very trade it was triggered for.
        if (result is RecordTradeResult.Recorded) {
            backupRequests.requestUpload(BackupReason.BOND_TRADE)
        }
        return result
    }
    /**
     * The instrument name, as a cash row reads in the list.
     *
     * The name alone, not "Купівля ОвДП" — the row already says which way the money went, in
     * the sign of the amount and in the note, and a title that repeated it made a list of
     * trades read as a list of sentences about the same instrument.
     */
    private fun bondTitle(bondName: String): String = bondName

    /**
     * What was done with it and at what price, on the line under the title.
     *
     * The side is here rather than in the title because a transfer is drawn in a neutral
     * colour on purpose: a purchase is not an expense and should not be dressed as one. With
     * that colouring gone, the note is the only thing saying which way the trade went, and
     * the two rows of a round trip would otherwise be indistinguishable.
     *
     * The price is printed as money in the bond's currency — 1 020.00 ₴ — matching what the
     * user typed, and not as a percentage, which is what the whole storage change removed.
     */
    private fun tradeNote(side: BondTradeSide, quantity: Int, price: Double, currency: String): String =
        "%s %d шт. @ %s".format(tradeWord(side), quantity, MoneyFormat.format(price, currency))

    /**
     * What the side is called, where the row has no other word for it.
     *
     * «Погашення» rather than «Продаж» for a redemption, because nobody bought the bonds back:
     * the issuer paid them. The price in a redemption's note is the per-bond figure the total
     * the user typed works out to, which for a redemption at par is the nominal.
     */
    private fun tradeWord(side: BondTradeSide): String = when (side) {
        BondTradeSide.BUY -> "Купівля"
        BondTradeSide.SELL -> "Продаж"
        BondTradeSide.REDEMPTION -> "Погашення"
    }

    private fun Bond.toEntity(isin: String): BondEntity =
        BondEntity(
            isin = isin,
            name = name,
            nominal = nominal,
            nominalCurrency = nominalCurrency,
            couponPercent = couponPercent,
            couponPeriodMonths = couponPeriodMonths,
            maturityDate = maturityDate
        )

    private companion object {
        /**
         * The category every bond cash movement is filed under.
         *
         * Fixed, not a user choice: a bond trade is an investment by construction, and
         * letting the category follow the type would put a bond purchase under groceries
         * the moment someone picked the wrong chip.
         */
        const val CATEGORY = "investments"
    }
}

/** The outcome of [BondRepository.recordTrade]. */
sealed interface RecordTradeResult {
    data class Recorded(val tradeId: Long, val transactionId: Long) : RecordTradeResult

    /**
     * The trade was refused. [problem] names which refusal, because the sentence belongs to the
     * screen and a sentence written here would be English on a Ukrainian device.
     */
    data class Invalid(val problem: BondTradeProblem) : RecordTradeResult

    /**
     * More bonds sold than are held.
     *
     * [held] is the real number, so the form can say how many are available rather than
     * just refusing.
     */
    data class Oversell(val held: Int) : RecordTradeResult
}

/** The outcome of [BondRepository.updateTerms]. */
sealed interface UpdateTermsResult {

    /** The stored terms now read as the form did, and a backup was asked for. */
    data object Updated : UpdateTermsResult

    /**
     * There was nothing to write: either every field already matched, or no such bond is
     * stored. Deliberately not a failure — a form saved without a change is not an error, and
     * there is nothing to upload.
     */
    data object Unchanged : UpdateTermsResult

    /**
     * The nominal currency would have changed on a bond that already has trades, which would
     * reinterpret every stored price and НКД against a different currency. Nothing was written.
     */
    data object CurrencyLocked : UpdateTermsResult
}
