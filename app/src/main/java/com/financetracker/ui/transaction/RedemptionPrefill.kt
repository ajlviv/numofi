package com.financetracker.ui.transaction

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.financetracker.model.BondPosition
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the trade form is opened with when the record-redemption button is pressed, so the
 * form starts where the user was rather than empty.
 *
 * A snapshot of the position at the moment the button was tapped, not a live view of it: the
 * form is a draft the user still confirms, and the oversell guard in the repository still
 * covers a quantity that went stale between the tap and the save. Everything here is a
 * primitive so the whole thing rides `rememberSaveable` through rotation and process death
 * without a parcelable plugin — the project has none, and six fields are not a reason to add
 * one. A bank code of "" is restored as null; bank codes are identifiers and never blank.
 */
data class RedemptionPrefill(
    val isin: String,
    val quantity: Int,
    val nominal: Double,
    val currency: String,
    val maturityDate: LocalDate,
    val bankCode: String?
) {
    companion object {
        /** The prefill for a held position, or null when the bond has no recorded maturity. */
        fun from(position: BondPosition): RedemptionPrefill? {
            val millis = position.bond.maturityDate ?: return null
            return RedemptionPrefill(
                isin = position.bond.isin,
                quantity = position.quantity,
                nominal = position.bond.nominal,
                currency = position.bond.nominalCurrency,
                maturityDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate(),
                bankCode = position.lastBankCode
            )
        }

        val Saver: Saver<RedemptionPrefill?, Any> = listSaver(
            save = { value ->
                value?.let {
                    listOf(it.isin, it.quantity, it.nominal, it.currency, it.maturityDate.toEpochDay(), it.bankCode.orEmpty())
                } ?: emptyList()
            },
            restore = { list ->
                if (list.isEmpty()) null else RedemptionPrefill(
                    isin = list[0] as String,
                    quantity = list[1] as Int,
                    nominal = list[2] as Double,
                    currency = list[3] as String,
                    maturityDate = LocalDate.ofEpochDay(list[4] as Long),
                    bankCode = (list[5] as String).ifBlank { null }
                )
            }
        )
    }
}
