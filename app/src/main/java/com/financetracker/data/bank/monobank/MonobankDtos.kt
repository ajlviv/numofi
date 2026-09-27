package com.financetracker.data.bank.monobank

import com.google.gson.annotations.SerializedName

// GET /personal/client-info
data class ClientInfoResponse(
    @SerializedName("clientId") val clientId: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("webHookUrl") val webHookUrl: String?,
    @SerializedName("permissions") val permissions: String?,
    @SerializedName("accounts") val accounts: List<MonobankAccount>?,
    @SerializedName("jars") val jars: List<MonobankJar>?
)

data class MonobankAccount(
    @SerializedName("id") val id: String?,
    @SerializedName("sendId") val sendId: String?,
    @SerializedName("balance") val balance: Long?,
    @SerializedName("creditLimit") val creditLimit: Long?,
    @SerializedName("type") val type: String?,
    @SerializedName("currencyCode") val currencyCode: Int?,
    @SerializedName("cashbackType") val cashbackType: String?,
    @SerializedName("maskedPan") val maskedPan: List<String>?,
    @SerializedName("iban") val iban: String?
)

data class MonobankJar(
    @SerializedName("id") val id: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("currencyCode") val currencyCode: Int?,
    @SerializedName("balance") val balance: Long?,
    @SerializedName("goal") val goal: Long?
)

// GET /personal/statement/{account}/{from}/{to}
data class StatementItemResponse(
    @SerializedName("id") val id: String?,
    @SerializedName("time") val time: Long?,
    @SerializedName("description") val description: String?,
    @SerializedName("mcc") val mcc: Int?,
    @SerializedName("originalMcc") val originalMcc: Int?,
    @SerializedName("hold") val hold: Boolean?,
    @SerializedName("amount") val amount: Long?,
    @SerializedName("operationAmount") val operationAmount: Long?,
    @SerializedName("currencyCode") val currencyCode: Int?,
    @SerializedName("commissionRate") val commissionRate: Double?,
    @SerializedName("cashbackAmount") val cashbackAmount: Long?,
    @SerializedName("balance") val balance: Long?,
    @SerializedName("comment") val comment: String?,
    @SerializedName("receiptId") val receiptId: String?,
    @SerializedName("invoiceId") val invoiceId: String?,
    @SerializedName("counterEdrpou") val counterEdrpou: String?,
    @SerializedName("counterIban") val counterIban: String?,
    @SerializedName("counterName") val counterName: String?
)

// GET /bank/currency
data class CurrencyInfoResponse(
    @SerializedName("currencyCodeA") val currencyCodeA: Int,
    @SerializedName("currencyCodeB") val currencyCodeB: Int,
    @SerializedName("date") val date: Long,
    @SerializedName("rateSell") val rateSell: Double,
    @SerializedName("rateBuy") val rateBuy: Double,
    @SerializedName("rateCross") val rateCross: Double
)

// GET /bank/sync
data class BankSyncResponse(
    @SerializedName("serverKeyId") val serverKeyId: String?,
    @SerializedName("serverPubKey") val serverPubKey: String?,
    @SerializedName("serverTimeMsec") val serverTimeMsec: Long?
)
