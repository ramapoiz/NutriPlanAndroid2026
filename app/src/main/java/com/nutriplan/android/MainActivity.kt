package com.nutriplan.android

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class FoodEntry(val id: Long, val name: String, val grams: Int, val kcal: Int, val protein: Double, val carbs: Double, val fat: Double, val date: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NutriPlanScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NutriPlanScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("nutriplan", 0) }
    val scope = rememberCoroutineScope()
    val entries = remember { mutableStateListOf<FoodEntry>().apply { addAll(loadEntries(prefs.getString("entries", "[]") ?: "[]")) } }
    var backendUrl by remember { mutableStateOf(prefs.getString("backend_url", "") ?: "") }
    var targetKcal by remember { mutableStateOf(prefs.getInt("target_kcal", 2000).toString()) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var foodName by remember { mutableStateOf("") }
    var grams by remember { mutableStateOf("250") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("0") }
    var carbs by remember { mutableStateOf("0") }
    var fat by remember { mutableStateOf("0") }
    var status by remember { mutableStateOf("Haz una foto de tu plato para estimar sus calorías con IA.") }
    var busy by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }
    val todayEntries = entries.filter { it.date == today }
    val totalKcal = todayEntries.sumOf { it.kcal }
    val totalProtein = todayEntries.sumOf { it.protein }
    val totalCarbs = todayEntries.sumOf { it.carbs }
    val totalFat = todayEntries.sumOf { it.fat }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            photo = bitmap
            if (backendUrl.isBlank()) {
                status = "Configura primero la URL del servidor de IA en Ajustes."
            } else {
                busy = true
                status = "Analizando la foto…"
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { analyzePhoto(bitmap, backendUrl.trim()) }
                        foodName = result.optString("name", "Alimento identificado")
                        grams = result.optInt("grams", 250).toString()
                        kcal = result.optInt("kcal", 0).toString()
                        protein = result.optDouble("protein", 0.0).toString()
                        carbs = result.optDouble("carbs", 0.0).toString()
                        fat = result.optDouble("fat", 0.0).toString()
                        status = "Estimación lista. Revisa el alimento y la cantidad antes de guardarlos."
                    } catch (e: Exception) {
                        status = "No se pudo analizar la foto: ${e.message ?: "error de conexión"}. Revisa la URL y que el servidor esté activo."
                    } finally { busy = false }
                }
            }
        } else status = "No se ha capturado ninguna foto."
    }

    MaterialTheme {
        Scaffold(topBar = {
            TopAppBar(title = { Text("NutriPlan") }, actions = {
                TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "Cerrar" else "Ajustes") }
            })
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Tu resumen de hoy", style = MaterialTheme.typography.headlineSmall)
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("$totalKcal kcal", style = MaterialTheme.typography.headlineMedium)
                        val goal = targetKcal.toIntOrNull()?.coerceAtLeast(1) ?: 2000
                        LinearProgressIndicator(progress = { (totalKcal.toFloat() / goal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("Objetivo: $goal kcal · ${((totalKcal.toFloat() / goal) * 100).roundToInt()}%")
                        Text("Proteínas ${totalProtein.roundToInt()} g · Hidratos ${totalCarbs.roundToInt()} g · Grasas ${totalFat.roundToInt()} g")
                    }
                }
                if (showSettings) {
                    Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Configuración", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(value = backendUrl, onValueChange = { backendUrl = it }, label = { Text("URL del servidor de IA (HTTPS)") }, placeholder = { Text("https://tu-servidor.example.com") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = targetKcal, onValueChange = { targetKcal = it.filter(Char::isDigit) }, label = { Text("Objetivo diario de kcal") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Button(onClick = { prefs.edit().putString("backend_url", backendUrl.trim()).putInt("target_kcal", targetKcal.toIntOrNull() ?: 2000).apply(); status = "Ajustes guardados." }, modifier = Modifier.fillMaxWidth()) { Text("Guardar ajustes") }
                        Text("No pongas tu clave API aquí. La clave debe permanecer en el servidor, nunca dentro de la APK.", style = MaterialTheme.typography.bodySmall)
                    } }
                }
                Text("Añadir comida con cámara", style = MaterialTheme.typography.titleLarge)
                Button(onClick = { cameraLauncher.launch(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.AddAPhoto, contentDescription = null)
                    Spacer(Modifier.width(8.dp)); Text(if (busy) "Analizando…" else "Hacer foto y calcular calorías")
                }
                photo?.let { bitmap -> Image(bitmap.asImageBitmap(), contentDescription = "Foto de la comida", modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) }
                Text(status, style = MaterialTheme.typography.bodyMedium)
                Text("Revisa el resultado", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = foodName, onValueChange = { foodName = it }, label = { Text("Alimento / plato") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = grams, onValueChange = { grams = it.filter(Char::isDigit) }, label = { Text("Cantidad (g)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = kcal, onValueChange = { kcal = it.filter(Char::isDigit) }, label = { Text("Calorías") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = protein, onValueChange = { protein = it }, label = { Text("Proteína (g)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = carbs, onValueChange = { carbs = it }, label = { Text("Hidratos (g)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = fat, onValueChange = { fat = it }, label = { Text("Grasa (g)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Button(onClick = {
                    val entry = FoodEntry(System.currentTimeMillis(), foodName.ifBlank { "Comida" }, grams.toIntOrNull() ?: 0, kcal.toIntOrNull() ?: 0, protein.toDoubleOrNull() ?: 0.0, carbs.toDoubleOrNull() ?: 0.0, fat.toDoubleOrNull() ?: 0.0, today)
                    entries.add(0, entry); prefs.edit().putString("entries", saveEntries(entries)).apply()
                    foodName = ""; grams = "250"; kcal = ""; protein = "0"; carbs = "0"; fat = "0"; photo = null
                    status = "Comida guardada en este dispositivo."
                }, modifier = Modifier.fillMaxWidth(), enabled = foodName.isNotBlank() && (kcal.toIntOrNull() != null)) { Text("Guardar comida") }

                Text("Comidas de hoy", style = MaterialTheme.typography.titleLarge)
                if (todayEntries.isEmpty()) Text("Todavía no has registrado comidas.")
                todayEntries.forEach { entry ->
                    Card { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.titleMedium)
                            Text("${entry.grams} g · ${entry.kcal} kcal")
                            Text("P ${entry.protein} g · H ${entry.carbs} g · G ${entry.fat} g", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { entries.remove(entry); prefs.edit().putString("entries", saveEntries(entries)).apply() }) { Icon(Icons.Default.Delete, contentDescription = "Eliminar comida") }
                    } }
                }
                Text("Las calorías y cantidades estimadas por IA pueden ser inexactas. Comprueba etiquetas y porciones; no es consejo médico.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun analyzePhoto(bitmap: Bitmap, baseUrl: String): JSONObject {
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
    val base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    val urlString = baseUrl.trimEnd('/') + "/analyze-food"
    val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 20000; readTimeout = 60000; doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
    }
    try {
        val body = JSONObject().put("imageBase64", base64).put("mimeType", "image/jpeg").toString().toByteArray(Charsets.UTF_8)
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val response = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw IllegalStateException("Servidor HTTP $code: ${response.take(250)}")
        val root = JSONObject(response)
        return root.getJSONObject("food")
    } finally { conn.disconnect() }
}

private fun loadEntries(raw: String): List<FoodEntry> = try {
    val arr = JSONArray(raw)
    (0 until arr.length()).map { i -> arr.getJSONObject(i).let { o -> FoodEntry(o.getLong("id"), o.getString("name"), o.getInt("grams"), o.getInt("kcal"), o.optDouble("protein"), o.optDouble("carbs"), o.optDouble("fat"), o.getString("date")) } }
} catch (_: Exception) { emptyList() }

private fun saveEntries(entries: List<FoodEntry>): String {
    val arr = JSONArray()
    entries.forEach { e -> arr.put(JSONObject().put("id", e.id).put("name", e.name).put("grams", e.grams).put("kcal", e.kcal).put("protein", e.protein).put("carbs", e.carbs).put("fat", e.fat).put("date", e.date)) }
    return arr.toString()
}
