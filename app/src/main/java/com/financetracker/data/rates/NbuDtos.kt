package com.financetracker.data.rates

import com.google.gson.annotations.SerializedName

// GET /NBUStatService/v1/statdirectory/exchange
//
// Three of the row's fields, and all three nullable on purpose. NBU sends `special: "N"` on
// the dollar row and omits `rate` entirely on anything it has nothing for; a nullable Double
// is what lets the parser tell "absent" from "zero". Gson's handling of a missing primitive
// is to supply 0.0 without complaint, so a non-null field here would quietly put a zero rate
// into the cache and leave it there for something to divide by.
data class NbuRateResponse(
    @SerializedName("cc") val currencyCode: String?,
    @SerializedName("rate") val rate: Double?,
    @SerializedName("exchangedate") val exchangeDate: String?
)
