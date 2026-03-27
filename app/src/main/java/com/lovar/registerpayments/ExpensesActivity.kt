package com.lovar.registerpayments

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.navigation.NavigationView
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.lovar.registerpayments.databinding.ActivityExpensesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ExpensesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExpensesBinding
    private val adapter = ExpensesAdapter()
    private val client = OkHttpClient()
    private val gson = Gson()

    // Rango seleccionado para consulta (millis)
    private var selectedStart: Long = 0L
    private var selectedEnd: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ...existing inflate...
        binding = ActivityExpensesBinding.inflate(layoutInflater)

        // --- Nuevo: DrawerLayout con NavigationView, usando wrapper para el contenido ---
        val drawer = DrawerLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            id = View.generateViewId()
        }
        val contentView = binding.root

        // Wrapper para evitar manipular directamente un ScrollView raíz
        val contentWrapper = FrameLayout(this).apply {
            id = View.generateViewId()
            addView(contentView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }

        val navView = NavigationView(this).apply {
            id = View.generateViewId()
            layoutParams = DrawerLayout.LayoutParams(
                dpToPx(260),
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.START
            }
        }

        drawer.addView(contentWrapper, DrawerLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        drawer.addView(navView)
        setContentView(drawer)
        // --- fin nuevo ---

        // construir menú dinámico
        val menu = navView.menu
        menu.clear()
        menu.add("Inicio").setOnMenuItemClickListener {
            drawer.closeDrawer(navView)
            startActivity(Intent(this, MainActivity::class.java))
            true
        }
        menu.add("Cambiar usuario").setOnMenuItemClickListener {
            drawer.closeDrawer(navView)
            // abrir MainActivity para cambiar usuario (o volver)
            startActivity(Intent(this, MainActivity::class.java))
            true
        }
        menu.add("Cerrar").setOnMenuItemClickListener {
            drawer.closeDrawer(navView)
            finish()
            true
        }

        // hamburguesa
        val ham = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_sort_by_size)
            background = null
            setOnClickListener { drawer.openDrawer(navView) }
        }
        val hamLp = DrawerLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            topMargin = dpToPx(8)
            marginStart = dpToPx(8)
        }
        // Añadir el botón hamburguesa al wrapper del contenido para evitar conflictos con ScrollView padre
        contentWrapper.addView(ham, hamLp)

        // ...existing initialization continues ...
        binding.rvExpenses.layoutManager = LinearLayoutManager(this)
        binding.rvExpenses.adapter = adapter

        // Inicializar rango al mes actual
        setSelectedMonthTo(Calendar.getInstance().get(Calendar.YEAR), Calendar.getInstance().get(Calendar.MONTH))
        ensureRangeViews()

        // asegurar el botón volver si es necesario
        ensureBackButton()

        // safe click (en caso de que no exista, no explota)
        try { binding.btnRefresh.setOnClickListener { loadExpenses() } } catch (_: Throwable) { /* no-op */ }

        // aplicar estilo al botón de refresco si existe
        (findViewByIdSafe("btnRefresh") as? Button)?.let { applyRoundedStyle(it) }

        loadExpenses()
    }

    // Asegura existencia de btnSelectMonth y tvRange; si no están los crea y los agrega al layout principal
    private fun ensureRangeViews() {
        // evitar doble inserción: buscar contenedor creado anteriormente
        val existingContainer = binding.root.findViewWithTag<View>("expenses_range_container")
        if (existingContainer != null) {
            // ya existe, solo aseguramos el listener en el botón si corresponde
            val existingBtn = binding.root.findViewWithTag<View>("btnSelectMonth") as? View
            existingBtn?.setOnClickListener { showMonthPicker() }
            updateRangeTextInView()
            return
        }

        // si ya existe en layout (XML) solo actualizar texto y listener por id/tag
        val existingBtn = findViewByIdSafe("btnSelectMonth")
        if (existingBtn != null) {
            existingBtn.setOnClickListener { showMonthPicker() }
            updateRangeTextInView()
            return
        }

        // decidir contenedor seguro (similar a MainActivity approach)
        val rootView = binding.root
        var parentForControls: ViewGroup? = null
        if (rootView is android.widget.ScrollView) {
            val firstChild = rootView.getChildAt(0)
            if (firstChild is ViewGroup) parentForControls = firstChild
        } else if (rootView is ViewGroup) {
            parentForControls = rootView
        }

        if (parentForControls != null) {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                tag = "expenses_range_container"
            }

            val tv = TextView(this).apply {
                // usar tag para encontrarlo luego
                tag = "tvRange"
                textSize = 14f
                setPadding(8, 8, 8, 8)
            }

            val btn = Button(this).apply {
                tag = "btnSelectMonth"
                text = "Seleccionar mes"
                setOnClickListener { showMonthPicker() }
            }

            val marginDp = 8
            val marginPx = (marginDp * resources.displayMetrics.density).toInt()
            val lp = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = marginPx
            }

            // Añadir elementos al container y luego al parent (container permite dos vistas en línea)
            val lpChild = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = marginPx
            }
            container.addView(tv, lpChild)
            val lpBtn = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            container.addView(btn, lpBtn)

            parentForControls.addView(container, lp)
        }

        // setear texto del rango en la vista (si existe)
        updateRangeTextInView()

        // Si el botón fue creado dinámicamente, aplicarle el estilo
        val createdBtn = binding.root.findViewWithTag<View>("btnSelectMonth") as? Button
        createdBtn?.let { applyRoundedStyle(it) }
    }

    private fun updateRangeTextInView() {
        // buscar TextView creado previamente por tag "tvRange"
        val tv = (binding.root.findViewWithTag<View>("tvRange") as? TextView)
            ?: run {
                val id = resources.getIdentifier("tvRange", "id", packageName)
                if (id != 0) binding.root.findViewById(id) as? TextView else null
            }

        tv?.text = "Rango: ${niceRange(selectedStart, selectedEnd)}"
    }

    // Abre DatePickerDialog para seleccionar un día del mes (usamos solo month/year)
    private fun showMonthPicker() {
        val cal = Calendar.getInstance()
        // inicializar con mes seleccionado actual si existe
        if (selectedStart != 0L) {
            val tmp = Calendar.getInstance().apply { timeInMillis = selectedStart }
            cal.set(Calendar.YEAR, tmp.get(Calendar.YEAR))
            cal.set(Calendar.MONTH, tmp.get(Calendar.MONTH))
        }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH)

        val dp = DatePickerDialog(this, { _: DatePicker, selYear: Int, selMonth: Int, _selDay: Int ->
            // cuando seleccionan una fecha, usamos el mes/año seleccionado
            setSelectedMonthTo(selYear, selMonth)
            updateRangeTextInView()
            // recargar datos para el nuevo mes
            loadExpenses()
        }, year, month, 1)

        dp.show()
    }

    // Establece selectedStart/selectedEnd para un mes dado (selMonth es 0-based)
    private fun setSelectedMonthTo(selYear: Int, selMonth: Int) {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(Calendar.YEAR, selYear)
        cal.set(Calendar.MONTH, selMonth)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        selectedStart = cal.timeInMillis

        cal.add(Calendar.MONTH, 1)
        cal.add(Calendar.MILLISECOND, -1)
        selectedEnd = cal.timeInMillis
    }

    private fun loadExpenses() {
        binding.progress.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE

        // usar selectedStart / selectedEnd en lugar del mes actual fijo
        val start = selectedStart.takeIf { it > 0 } ?: run {
            val c = Calendar.getInstance()
            c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            c.timeInMillis
        }
        val end = selectedEnd.takeIf { it > 0 } ?: run {
            val c = Calendar.getInstance()
            c.set(Calendar.DAY_OF_MONTH, 1); c.add(Calendar.MONTH, 1); c.add(Calendar.MILLISECOND, -1)
            c.timeInMillis
        }

        lifecycleScope.launch {
            runCatching { fetchExpensesFromServer() }
                .onSuccess { list ->
                    val filtered = list.filter { it.timestamp in start..end }
                        .sortedByDescending { it.id }

                    adapter.setItems(filtered)
                    binding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE

                    // actualizar rango mostrado si hay tvRange
                    updateRangeTextInView()
                }
                .onFailure { e ->
                    Log.e("ExpensesActivity", "Error cargando expensas", e)
                    binding.tvEmpty.text = "Error: ${e.message}"
                    binding.tvEmpty.visibility = View.VISIBLE
                }
                .also {
                    binding.progress.visibility = View.GONE
                }
        }
    }

    // Ahora la petición incluye ?usuario=<USUARIO guardado>
    private suspend fun fetchExpensesFromServer(): List<MyExpense> = withContext(Dispatchers.IO) {
        // Leer usuario guardado en SharedPreferences (misma clave que MainActivity)
        val prefs = getSharedPreferences("register_prefs", Context.MODE_PRIVATE)
        val usuario = prefs.getString("USUARIO", "")?.trim().orEmpty()
        if (usuario.isBlank()) {
            // lanzar excepción para que se maneje en la coroutine y muestre mensaje en UI
            throw Exception("USUARIO no configurado. Abra la app y establezca el USUARIO en la pantalla inicial.")
        }

        val encoded = URLEncoder.encode(usuario, "UTF-8")
        val url = "https://lovar.com.ar/expenses?usuario=$encoded"

        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            Log.d("ExpensesActivity", "HTTP ${resp.code} -> $body")
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: $body")

            val root: JsonElement = gson.fromJson(body, JsonElement::class.java)
            val out = ArrayList<MyExpense>()

            fun parseElement(elem: JsonElement) {
                when {
                    elem.isJsonArray -> {
                        elem.asJsonArray.forEach { parseElement(it) }
                    }
                    elem.isJsonObject -> {
                        val obj = elem.asJsonObject

                        // Si el objeto contiene la lista en una key conocida, recorrer esa lista
                        val arrayKeys = listOf("expenses", "data", "items", "results")
                        for (k in arrayKeys) {
                            if (obj.has(k) && obj.get(k).isJsonArray) {
                                obj.getAsJsonArray(k).forEach { parseElement(it) }
                                return
                            }
                        }

                        // id: puede venir como number o string
                        val id = try {
                            if (obj.has("id") && obj.get("id").isJsonPrimitive) {
                                val prim = obj.get("id").asJsonPrimitive
                                if (prim.isNumber) prim.asLong else prim.asString.toLongOrNull() ?: 0L
                            } else 0L
                        } catch (_: Throwable) { 0L }

                        // item/place/amount parsing robusto
                        val item = if (obj.has("item") && obj.get("item").isJsonPrimitive) obj.get("item").asString else (if (obj.has("raw_text") && obj.get("raw_text").isJsonPrimitive) obj.get("raw_text").asString else "")
                        val place = if (obj.has("place") && obj.get("place").isJsonPrimitive) obj.get("place").asString else ""
                        val amount = try {
                            if (obj.has("amount") && obj.get("amount").isJsonPrimitive) {
                                val prim = obj.get("amount").asJsonPrimitive
                                if (prim.isNumber) prim.asDouble else prim.asString.toDoubleOrNull()
                            } else null
                        } catch (_: Throwable) { null }

                        // timestamp puede venir como number o string
                        var timestamp = try {
                            if (obj.has("timestamp") && obj.get("timestamp").isJsonPrimitive) {
                                val prim = obj.get("timestamp").asJsonPrimitive
                                if (prim.isNumber) prim.asLong else prim.asString.toLongOrNull() ?: 0L
                            } else 0L
                        } catch (_: Throwable) { 0L }

                        // Normalizar segundos->ms (si parece una marca en segundos)
                        if (timestamp in 1..9999999999L) timestamp *= 1000L

                        // user/USUARIO extraction
                        val user = when {
                            obj.has("USUARIO") && obj.get("USUARIO").isJsonPrimitive -> obj.get("USUARIO").asString
                            obj.has("user") && obj.get("user").isJsonPrimitive -> obj.get("user").asString
                            obj.has("usuario") && obj.get("usuario").isJsonPrimitive -> obj.get("usuario").asString
                            obj.has("username") && obj.get("username").isJsonPrimitive -> obj.get("username").asString
                            else -> ""
                        }

                        out.add(MyExpense(id, item, place, amount, timestamp, user))
                    }
                    else -> {
                        // ignore primitives
                    }
                }
            }

            parseElement(root)
            return@withContext out
        }
    }

    // Opcional: helper para mostrar rango consultado
    private fun niceRange(start: Long, end: Long): String {
        val fmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        return "${fmt.format(start)} - ${fmt.format(end)}"
    }

    // Helper para buscar vista por id si existe (fallback más robusto)
    private fun findViewByIdSafe(name: String): View? {
        val id = resources.getIdentifier(name, "id", packageName)
        if (id == 0) return null

        // 1) intentar en binding.root
        try {
            val v = binding.root.findViewById<View?>(id)
            if (v != null) return v
        } catch (_: Throwable) { /* no-op */ }

        // 2) intentar en activity (findViewById)
        try {
            val v2 = findViewById<View?>(id)
            if (v2 != null) return v2
        } catch (_: Throwable) { /* no-op */ }

        // 3) fallback decorView
        val decor = window.decorView
        return try { decor.findViewById<View?>(id) } catch (_: Throwable) { null }
    }

    // Añade un botón "Volver" (tag = "btnBackExpenses") si no existe, lo inserta en un contenedor seguro y llama finish() al click
    private fun ensureBackButton() {
        // evitar duplicados
        val existing = binding.root.findViewWithTag<View>("btnBackExpenses")
        if (existing != null) return

        // determinar contenedor donde insertar: si root es ScrollView tomar su primer hijo cuando sea ViewGroup
        val rootView = binding.root
        var parentForButton: ViewGroup? = null
        if (rootView is android.widget.ScrollView) {
            val firstChild = rootView.getChildAt(0)
            if (firstChild is ViewGroup) parentForButton = firstChild
        } else if (rootView is ViewGroup) {
            parentForButton = rootView
        }

        if (parentForButton == null) return

        val btn = Button(this).apply {
            tag = "btnBackExpenses"
            text = "Volver"
            setOnClickListener { finish() }
        }

        // aplicar estilo al botón creado
        applyRoundedStyle(btn)

        val marginDp = 8
        val marginPx = (marginDp * resources.displayMetrics.density).toInt()
        val lp = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = marginPx }

        // Insertar al principio para que quede arriba; si prefieres al final cambia index
        parentForButton.addView(btn, 0, lp)
    }

    // Nuevo: convierte dp a px
    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    // Nuevo: aplicar fondo redondeado y padding a un Button
    private fun applyRoundedStyle(btn: Button, bgColor: Int = Color.parseColor("#03A9F4"), textColor: Int = Color.WHITE) {
        val radius = dpToPx(8).toFloat()
        val stroke = dpToPx(1)
        val gd = GradientDrawable().apply {
            cornerRadius = radius
            setColor(bgColor)
            setStroke(stroke, Color.parseColor("#33000000"))
        }
        btn.background = gd
        btn.setTextColor(textColor)
        val hPad = dpToPx(12)
        val vPad = dpToPx(8)
        btn.setPadding(hPad, vPad, hPad, vPad)
        btn.elevation = dpToPx(2).toFloat()
    }
}
