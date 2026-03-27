package com.lovar.registerpayments.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface ApiService {
    @GET("expenses")
    suspend fun getExpenses(): List<ExpenseResponse>

    @POST("expenses")
    suspend fun createExpense(@Body req: ExpenseRequest): ExpenseResponse
}
