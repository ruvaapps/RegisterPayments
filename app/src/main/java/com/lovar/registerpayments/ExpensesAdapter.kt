package com.lovar.registerpayments

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExpensesAdapter : RecyclerView.Adapter<ExpensesAdapter.VH>() {

    private var items: List<MyExpense> = emptyList()
    private val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    fun setItems(list: List<MyExpense>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_expense, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        val itemPlace = "${e.item} — ${e.place}"
        val amount = e.amount?.let { " $${"%.2f".format(it)}" } ?: ""
        val date = dateFmt.format(Date(e.timestamp))

        // Buscar TextViews de forma segura en tiempo de ejecución y hacer cast seguro (as?)
        val pkg = holder.itemView.context.packageName

        val idItemPlace = holder.itemView.resources.getIdentifier("tvItemPlace", "id", pkg)
        val tvItemPlace = if (idItemPlace != 0) holder.itemView.findViewById<View>(idItemPlace) as? TextView else null
        tvItemPlace?.text = itemPlace

        val idAmountDate = holder.itemView.resources.getIdentifier("tvAmountDate", "id", pkg)
        val tvAmountDate = if (idAmountDate != 0) holder.itemView.findViewById<View>(idAmountDate) as? TextView else null
        tvAmountDate?.text = "$amount · $date"

        val idUser = holder.itemView.resources.getIdentifier("tvUser", "id", pkg)
        val tvUser = if (idUser != 0) holder.itemView.findViewById<View>(idUser) as? TextView else null
        tvUser?.text = if (e.user.isNotBlank()) "Registrado por: ${e.user}" else ""
    }

    override fun getItemCount(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        // ViewHolder simple: no inicializar vistas aquí para evitar casts problemáticos en constructor
    }
}
