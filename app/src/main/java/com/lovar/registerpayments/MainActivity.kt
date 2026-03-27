package com.lovar.registerpayments

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.lovar.registerpayments.databinding.ActivityMainBinding
import com.lovar.registerpayments.network.ExpenseRequest
import com.lovar.registerpayments.network.RetrofitClient
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var transcript: String = ""

    // SpeechRecognizer
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var recognizerIntent: Intent
    private var listening = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // SharedPreferences para almacenar el USUARIO
    private val prefs by lazy { getSharedPreferences("register_prefs", Context.MODE_PRIVATE) }

    // Acción del broadcast para hotword detectada
    private val hotwordAction = "com.lovar.HOTWORD_DETECTED"

    // Receiver para activación por hotword (disparado desde el servicio)
    private val hotwordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Hotword detectado -> iniciar escucha activa en foreground UI
            mainHandler.post {
                toast("Hotword detectada: activando escucha")
                // iniciar la escucha interactiva existente
                if (!listening) startSpeechToText()
            }
        }
    }

    // Permission request (se mantiene)
    private val askMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // iniciar servicio que escucha en background/foreground
            startSpeechService()
        } else toast("Permiso de micrófono denegado")
    }

    // ...existing code up to onCreate...
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ...existing code up to binding inflate...
        binding = ActivityMainBinding.inflate(layoutInflater)

        // Inicializar SpeechRecognizer y intent (no inicia escucha todavía)
        initSpeechRecognizer()

        // --- Nuevo: crear DrawerLayout programáticamente y NavigationView ---
        val drawer = DrawerLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            id = View.generateViewId()
        }

        // tu layout principal (inflado por viewBinding)
        val contentView = binding.root

        // Wrappear el contentView en un FrameLayout para no manipular directamente el ScrollView raíz
        val contentWrapper = FrameLayout(this).apply {
            id = View.generateViewId()
            addView(contentView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }

        // NavigationView lateral (menú)
        val navView = NavigationView(this).apply {
            id = View.generateViewId()
            // opcional: background/width
            layoutParams = DrawerLayout.LayoutParams(
                dpToPx(260),
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.START
            }
        }

        // Añadir wrapper y nav al DrawerLayout (ahora no añadimos binding.root directamente)
        drawer.addView(contentWrapper, DrawerLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        drawer.addView(navView)

        // Establecer DrawerLayout como vista de la Activity
        setContentView(drawer)
        // --- fin nuevo ---

        // construir menú dinámicamente (items y listeners)
        val menu = navView.menu
        menu.clear()
        menu.add("Ver expensas").setOnMenuItemClickListener {
            drawer.closeDrawer(navView)
            startActivity(Intent(this, ExpensesActivity::class.java))
            true
        }
        menu.add("Cambiar usuario").setOnMenuItemClickListener {
            drawer.closeDrawer(navView)
            showChangeUsuarioDialog()
            true
        }
        menu.add("Cerrar app").setOnMenuItemClickListener {
            drawer.closeDrawer(navView)
            finish()
            true
        }

        // botón hamburguesa: abrir/cerrar drawer
        val ham = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_sort_by_size)
            background = null
            setOnClickListener { drawer.openDrawer(navView) }
            // margen y posición serán absolutos dentro del DrawerLayout
        }
        // agregar el botón sobre el contentView en la esquina superior izquierda
        val hamLp = DrawerLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            topMargin = dpToPx(8)
            marginStart = dpToPx(8)
        }
        // Añadir el botón hamburguesa al wrapper del contenido en lugar de al DrawerLayout
        // evita problemas cuando el contentView original es un ScrollView
        contentWrapper.addView(ham, hamLp)

        // Ahora continúa el resto de la inicialización original (listeners, etc.)
        // btnRecord / btnSave: buscarlos de forma segura (si existen)
        (findViewSafe("btnRecord") as? Button)?.setOnClickListener {
            // Si está escuchando, detener; si no, comprobar permiso y arrancar
            if (listening) stopSpeechListening() else ensureMicPermissionAndStart()
        }
        (findViewSafe("btnSave") as? Button)?.setOnClickListener { saveExpense() }

        // Listener seguro para el botón "Ver expensas"
        val btnView = findViewSafe("btnViewExpenses") as? View
        btnView?.setOnClickListener { startActivity(Intent(this, ExpensesActivity::class.java)) }

        // --- NUEVO: permitir cambiar usuario desde la UI ---
        // Si existe un botón con id "btnChangeUser" lo usamos (XML)
        (findViewSafe("btnChangeUser") as? View)?.setOnClickListener {
            showChangeUsuarioDialog()
        }
        // Si no existe en el layout, lo creamos dinámicamente y lo agregamos al root (ViewGroup)
        if (findViewSafe("btnChangeUser") == null) {
            val rootView = binding.root

            // Determinar contenedor donde insertar: si root es ScrollView tomar su primer hijo cuando sea ViewGroup
            var parentForButton: ViewGroup? = null
            if (rootView is android.widget.ScrollView) {
                val firstChild = rootView.getChildAt(0)
                if (firstChild is ViewGroup) parentForButton = firstChild
            } else if (rootView is ViewGroup) {
                parentForButton = rootView
            }

            if (parentForButton != null) {
                val btn = Button(this).apply {
                    text = "Cambiar usuario"
                    // asignar id dinámico para evitar colisiones
                    id = View.generateViewId()
                    setOnClickListener { showChangeUsuarioDialog() }
                }
                // aplicar estilo redondeado al botón creado dinámicamente
                applyRoundedStyle(btn)

                val marginDp = 8
                val marginPx = (marginDp * resources.displayMetrics.density).toInt()
                val lp = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = marginPx
                }
                parentForButton.addView(btn, lp)
            } else {
                // fallback seguro: no agregar para evitar crash
            }
        }
        // --- fin nuevo ---

        // Permitir cambiar usuario con long-press sobre el campo etUser (si existe)
        val etUserView = findViewSafe("etUser") as? EditText
        etUserView?.setOnLongClickListener {
            showChangeUsuarioDialog()
            true
        }

        // Asegurarse que hay un usuario configurado: si no, mostrar pantalla (dialog) inicial
        ensureUserPresent()

        // Si tenemos permiso, arrancar servicio de escucha continua
        startSpeechServiceIfPermitted()

        // Aplicar estilo a botones existentes si están en layout
        styleExistingButtons()
    }

    private fun startSpeechServiceIfPermitted() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startSpeechService()
        } else {
            // pedimos permiso; al conceder se llama a askMicPermission callback => ahora arranca el servicio
            askMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startSpeechService() {
        try {
            val svc = Intent(this, SpeechService::class.java)
            // use startForegroundService for Android O+
            ContextCompat.startForegroundService(this, svc)
        } catch (e: Throwable) {
            toast("No se pudo iniciar servicio de escucha: ${e.message}")
        }
    }

    // Inicializa SpeechRecognizer y RecognitionListener
    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Reconocimiento de voz no disponible en este dispositivo")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("es", "AR"))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                // opcional: indicar UI
                mainHandler.post { setTranscriptText("Escuchando...") }
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                listening = false
            }
            override fun onError(error: Int) {
                listening = false
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NETWORK -> "Error de red"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No se entendió"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconocedor ocupado"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Sin permiso"
                    else -> "Error de reconocimiento: $error"
                }
                mainHandler.post { setTranscriptText(msg) }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull().orEmpty()
                if (text.isNotBlank()) {
                    transcript = text
                    mainHandler.post {
                        setTranscriptText(text)
                        val (item, place, amount) = parsePurchase(text)
                        setParsedToInputs(item, place, amount)
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    mainHandler.post { setTranscriptText(partial) }
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startSpeechToText() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            askMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        try {
            speechRecognizer ?: initSpeechRecognizer()
            speechRecognizer?.startListening(recognizerIntent)
            listening = true
            setTranscriptText("Escuchando...")
        } catch (e: Exception) {
            toast("No se pudo iniciar reconocimiento: ${e.message}")
        }
    }

    private fun stopSpeechListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (_: Throwable) {}
        listening = false
        setTranscriptText("Detenido")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Throwable) {}
        speechRecognizer = null
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(hotwordAction)
        try {
            // En Android 14+ es obligatorio indicar si el receiver puede ser expuesto.
            if (Build.VERSION.SDK_INT >= 34) {
                // Registrar como NO exportado (recibe solo broadcasts del mismo app/process)
                registerReceiver(hotwordReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(hotwordReceiver, filter)
            }
        } catch (e: Exception) {
            // Fallback seguro para evitar crash si la overload no existe en tiempo de ejecución
            try { registerReceiver(hotwordReceiver, filter) } catch (_: Throwable) {}
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(hotwordReceiver) } catch (_: Throwable) {}
    }

    // Helper seguro: devolver view por nombre de id (o null)
    private fun findViewSafe(name: String): View? {
        val id = resources.getIdentifier(name, "id", packageName)
        if (id == 0) return null

        // 1) intentar en binding.root (si existe dentro del wrapper)
        try {
            binding.let {
                val v = it.root.findViewById<View?>(id)
                if (v != null) return v
            }
        } catch (_: Throwable) { /* no-op */ }

        // 2) intentar en activity (findViewById)
        try {
            val v2 = findViewById<View?>(id)
            if (v2 != null) return v2
        } catch (_: Throwable) { /* no-op */ }

        // 3) fallback: buscar en decorView
        val decor = window.decorView
        return try { decor.findViewById<View?>(id) } catch (_: Throwable) { null }
    }

    private fun setTranscriptText(text: String) {
        // actualiza transcript UI si existe
        val tv = findViewSafe("tvTranscript") as? TextView
        tv?.text = "Texto reconocido: $text"
    }

    // Reescribir setParsedToInputs para volcar item/place/amount en los EditText (si existen)
    private fun setParsedToInputs(item: String, place: String, amount: Double?) {
        (findViewSafe("etItem") as? EditText)?.setText(item)
        (findViewSafe("etPlace") as? EditText)?.setText(place)
        // colocar monto sin formato (ej: "150.5")
        val etAmount = (findViewSafe("etAmount") as? EditText)
        if (amount != null) {
            // mostrar con separador decimal con punto
            etAmount?.setText(String.format(Locale.US, "%.2f", amount))
        } else {
            // si no hay monto detectado, dejar vacio para que usuario complete
            // no tocar si ya había un valor
            if (etAmount?.text.isNullOrBlank()) etAmount?.setText("")
        }
    }

    private fun ensureMicPermissionAndStart() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) startSpeechToText() else askMicPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Obtener usuario almacenado
    private fun getStoredUsuario(): String {
        return prefs.getString("USUARIO", "")?.trim().orEmpty()
    }

    // Guardar usuario
    private fun setStoredUsuario(value: String) {
        prefs.edit().putString("USUARIO", value.trim()).apply()
    }

    // Comprobar y mostrar prompt si es necesario
    private fun ensureUserPresent() {
        val stored = getStoredUsuario()
        // si no hay usuario almacenado, forzar diálogo de ingreso
        if (stored.isBlank()) {
            showUsuarioDialog()
        } else {
            // si existe, volcarlo al etUser si existe en el layout
            (findViewSafe("etUser") as? EditText)?.setText(stored)
        }
    }

    // Diálogo modal para pedir el USUARIO (no cancelable)
    private fun showUsuarioDialog() {
        val input = EditText(this)
        input.hint = "Usuario"
        input.setSingleLine(true)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Ingrese USUARIO")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Guardar") { dlg: DialogInterface, _: Int ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isNotBlank()) {
                    setStoredUsuario(text)
                    // volcar al campo etUser si existe
                    (findViewSafe("etUser") as? EditText)?.setText(text)
                    dlg.dismiss()
                } else {
                    // Si está vacío volvemos a mostrar el diálogo (se fuerza ingreso)
                    dlg.dismiss()
                    showUsuarioDialog()
                }
            }
            .create()
        dialog.show()
    }

    // Reescribir saveExpense para usar usuario almacenado si no hay valor en etUser
    private fun saveExpense() {
        val etItem = (findViewSafe("etItem") as? EditText)
        val etPlace = (findViewSafe("etPlace") as? EditText)
        val etAmount = (findViewSafe("etAmount") as? EditText)
        val etUser = (findViewSafe("etUser") as? EditText)
        val tvStatus = (findViewSafe("tvStatus") as? TextView)
        val tvTranscript = (findViewSafe("tvTranscript") as? TextView)

        val item = etItem?.text?.toString()?.trim().orEmpty()
        val place = etPlace?.text?.toString()?.trim().orEmpty()
        val amount = etAmount?.text?.toString()?.trim()?.toDoubleOrNull()
        var user = etUser?.text?.toString()?.trim().orEmpty()
        val rawTranscript = tvTranscript?.text?.toString().orEmpty()

        // Si etUser está vacío, tomar usuario almacenado
        if (user.isBlank()) {
            user = getStoredUsuario()
            // si encontramos uno, volcarlo al campo (si existe)
            if (user.isNotBlank()) (etUser)?.setText(user)
        }

        if (item.isBlank() || place.isBlank()) {
            tvStatus?.text = "Completar qué compraste y dónde"
            return
        }

        if (user.isBlank()) {
            // Si aún falta el usuario, pedirlo con el diálogo
            tvStatus?.text = "Completar usuario (pantalla de inicio)"
            showUsuarioDialog()
            return
        }

        val req = ExpenseRequest(
            item = item,
            place = place,
            amount = amount,
            rawText = rawTranscript,
            timestamp = System.currentTimeMillis(),
            user = user
        )

        tvStatus?.text = "Guardando..."
        lifecycleScope.launch {
            runCatching {
                RetrofitClient.api.createExpense(req)
            }.onSuccess { resp ->
                tvStatus?.text = "Guardado (id=${resp.id})"
                etItem?.text?.clear()
                etPlace?.text?.clear()
                etAmount?.text?.clear()
                // conservar usuario
            }.onFailure { e ->
                tvStatus?.text = "Error guardando: ${e.message}"
            }
        }
    }

    // Nuevo parser robusto: intenta extraer item, place y amount (Double)
    private fun parsePurchase(text: String): Triple<String, String, Double?> {
        val normalized = text.trim()

        // 1) patrón explícito: "compré <item> en <place> por <amount>"
        val pattern1 = Regex("""(?i)(?:compr[eé]|pagu[eé]|gast[eé])\s+(.+?)\s+en\s+(.+?)\s+(?:por|a|al|con)?\s*\$?\s*([0-9]+(?:[.,][0-9]+)?)\b""")
        val m1 = pattern1.find(normalized)
        if (m1 != null) {
            val item = m1.groupValues[1].trim().replace(Regex("\\s+"), " ")
            val place = m1.groupValues[2].trim().replace(Regex("\\s+"), " ")
            val amt = m1.groupValues[3].replace(',', '.').toDoubleOrNull()
            return Triple(item, place, amt)
        }

        // 2) patrón sin "por": "compré <item> en <place> <amount>"
        val pattern2 = Regex("""(?i)(?:compr[eé]|pagu[eé]|gast[eé])\s+(.+?)\s+en\s+(.+?)\s+\$?\s*([0-9]+(?:[.,][0-9]+)?)\b""")
        val m2 = pattern2.find(normalized)
        if (m2 != null) {
            val item = m2.groupValues[1].trim().replace(Regex("\\s+"), " ")
            val place = m2.groupValues[2].trim().replace(Regex("\\s+"), " ")
            val amt = m2.groupValues[3].replace(',', '.').toDoubleOrNull()
            return Triple(item, place, amt)
        }

        // 3) patrón: "compré <item> por <amount> en <place>"
        val pattern3 = Regex("""(?i)(?:compr[eé]|pagu[eé]|gast[eé])\s+(.+?)\s+(?:por|a)\s+\$?\s*([0-9]+(?:[.,][0-9]+)?)\s+en\s+(.+?)\b""")
        val m3 = pattern3.find(normalized)
        if (m3 != null) {
            val item = m3.groupValues[1].trim().replace(Regex("\\s+"), " ")
            val amt = m3.groupValues[2].replace(',', '.').toDoubleOrNull()
            val place = m3.groupValues[3].trim().replace(Regex("\\s+"), " ")
            return Triple(item, place, amt)
        }

        // 4) Si no coincide, intentar extraer monto como el último número en la frase
        val numRegex = Regex("""([0-9]+(?:[.,][0-9]+)?)\b""")
        val nums = numRegex.findAll(normalized).map { it.value }.toList()
        val possibleAmount = if (nums.isNotEmpty()) nums.last().replace(',', '.').toDoubleOrNull() else null

        // intentar extraer item/place por "en"
        val inIndex = normalized.lowercase(Locale.getDefault()).indexOf(" en ")
        if (inIndex >= 0) {
            val before = normalized.substring(0, inIndex).trim()
            val after = normalized.substring(inIndex + 4).trim()
            // si after termina con número (monto), quitarlo
            val afterClean = after.replace(Regex("""\b[0-9]+(?:[.,][0-9]+)?\b\$?"""), "").trim()
            val item = before.replace(Regex("""(?i)^(compr[eé]|pagu[eé]|gast[eé])\b"""), "").trim().replace(Regex("\\s+"), " ")
            val place = afterClean.replace(Regex("\\s+"), " ")
            return Triple(item, place, possibleAmount)
        }

        // 5) fallback: tomar toda la frase como item, sin place
        val itemFallback = normalized.replace(Regex("""(?i)^(compr[eé]|pagu[eé]|gast[eé])\b"""), "").trim().replace(Regex("\\s+"), " ")
        return Triple(itemFallback, "", possibleAmount)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val MENU_CHANGE_USER_ID = 1
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Añadir opción de menú para cambiar usuario (sin crear resources XML)
        menu.add(0, MENU_CHANGE_USER_ID, 0, "Cambiar usuario")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_CHANGE_USER_ID -> {
                showChangeUsuarioDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // Diálogo para cambiar el USUARIO en cualquier momento (cancelable, prefill)
    private fun showChangeUsuarioDialog() {
        val current = getStoredUsuario()
        val input = EditText(this)
        input.hint = "Usuario"
        input.setSingleLine(true)
        if (current.isNotBlank()) input.setText(current)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Cambiar USUARIO")
            .setView(input)
            .setCancelable(true)
            .setPositiveButton("Guardar") { dlg: DialogInterface, _: Int ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isNotBlank()) {
                    setStoredUsuario(text)
                    (findViewSafe("etUser") as? EditText)?.setText(text)
                }
                dlg.dismiss()
            }
            .setNegativeButton("Cancelar") { dlg: DialogInterface, _: Int ->
                dlg.dismiss()
            }
            .create()
        dialog.show()
    }

    // Nuevo: convierte dp a px
    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    // Nuevo: aplicar fondo redondeado y padding a un Button
    private fun applyRoundedStyle(btn: Button, bgColor: Int = Color.parseColor("#6200EE"), textColor: Int = Color.WHITE) {
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

    // Nuevo: buscar algunos botones comunes y aplicarles el estilo (si existen)
    private fun styleExistingButtons() {
        val ids = listOf("btnRecord", "btnSave", "btnViewExpenses", "btnChangeUser")
        for (name in ids) {
            val v = findViewSafe(name)
            if (v is Button) applyRoundedStyle(v)
        }
        // intentar estilizar tvStatus como botón alternativo (no obligatorio)
    }
}
