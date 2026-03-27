package com.lovar.registerpayments

// Modelo local compartido para las expensas
data class MyExpense(
    val id: Long,
    val item: String,
    val place: String,
    val amount: Double? = null,
    val timestamp: Long = 0L,
    val user: String = ""   // nombre del usuario (coincide con lo que envía/recibe el backend)
)

