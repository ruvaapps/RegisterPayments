package com.lovar.registerpayments.network

// Expense enviado al backend (agregado 'user')
data class ExpenseRequest(
    val item: String,
    val place: String,
    val amount: Double?,
    val rawText: String,
    val timestamp: Long,
    val user: String
)

// Expense devuelto por el backend (user opcional)
data class ExpenseResponse(
    val id: Long,
    val item: String,
    val place: String,
    val amount: Double?,
    val raw_text: String? = null,
    val timestamp: Long,
    val user: String? = null
)
