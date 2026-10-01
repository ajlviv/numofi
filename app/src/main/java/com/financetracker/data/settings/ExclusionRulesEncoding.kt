package com.financetracker.data.settings

import com.financetracker.model.ExclusionRules
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser

/**
 * How the user's exclusion rules are written into the single string DataStore holds.
 *
 * **JSON, and not a joined string**, because a rule is arbitrary user text. A comma-joined
 * encoding would split `переказ, на карту` into two patterns that each match nothing — and that
 * failure is silent and total at once: the rule looks installed, the settings list shows it, and
 * no transaction is ever excluded. A delimiter that is a legal character is not a delimiter.
 *
 * Gson is the same library [BackupSnapshotCodec] uses, so nothing new is depended on.
 */
private val GSON = Gson()

/**
 * Reads the stored value.
 *
 * Forgiving at every step, because this runs inside a flow the settings screen observes and a
 * throw here takes that screen down rather than showing a rule that stopped working. A rule set
 * is a preference, not data the user cannot afford to lose, so an unreadable value reads as *no
 * rules* — off, visible, and fixable — rather than as an error.
 *
 * One unreadable entry costs that entry and not the rest: someone with three working rules and
 * one from a build that stored something richer keeps the three.
 */
internal fun readExclusionRules(raw: String?): ExclusionRules {
    if (raw.isNullOrBlank()) return ExclusionRules()

    val array: JsonArray = try {
        JsonParser.parseString(raw).takeIf { it.isJsonArray }?.asJsonArray ?: return ExclusionRules()
    } catch (e: RuntimeException) {
        // JsonSyntaxException for malformed text, IllegalStateException from the array accessor
        // for a value that parses but is not an array. Both mean the same thing here: something
        // wrote a shape this build does not read, so there are no rules to report.
        return ExclusionRules()
    }

    return ExclusionRules(
        array.mapNotNull { element ->
            // `isString()` and not merely `isJsonPrimitive()`, because Gson considers a number
            // and a boolean primitives too and would happily hand back "1" as a rule. A rule the
            // user never typed that matches any title containing a digit is worse than losing it.
            element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        }
    )
}

/**
 * Writes the set as one JSON array, in the order given.
 *
 * Order is preserved rather than sorted because the settings list is the user reading their own
 * rules in the order they added them, and a set would reorder them on the next reload.
 *
 * Idempotent on its own output, so a save that changed nothing does not churn the stored string.
 */
internal fun encodeExclusionRules(patterns: List<String>): String = GSON.toJson(patterns)
