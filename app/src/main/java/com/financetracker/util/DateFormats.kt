package com.financetracker.util

import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The app's dates, in one place.
 *
 * Five screens had grown their own pattern and two of them disagreed with the rest: the
 * transaction list and the detail card printed "Sep 14, 2026" (month first, an American reading
 * this app's users do not write) while the entry form, the bond card and the schedule card
 * printed "14 Sep 2026". The day-first order is the one that survives here, because it is the
 * one the majority already had and the one both shipped languages use.
 *
 * The locale is read per call rather than captured at class load, which is why these are
 * functions and not constants: a user who changes the language in Settings sees the change,
 * and a `val` in an object would have frozen the first locale the process ever saw.
 *
 * NBU's `dd.MM.yyyy` rate date is deliberately not routed through here. It is quoted as the bank
 * published it, and it is unambiguous in both languages.
 */
object DateFormats {

    /** "14 Sep 2026". */
    fun date(): DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())

    /** "14 Sep 2026, 09:30" — for a stored timestamp, where the time is part of the fact. */
    fun dateTime(): DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.getDefault())

    /** "14 Sep" — for a filter chip, which has no room for a year and was not asked for one. */
    fun shortDate(): DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
}