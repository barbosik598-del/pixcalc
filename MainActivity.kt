package com.aquacalc

import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.script.ScriptEngineManager

private val ComponentActivity.dataStore by preferencesDataStore(name = "aquacalc_settings")
private val AlphaKey = floatPreferencesKey("glass_alpha")
private val HapticsKey = booleanPreferencesKey("haptics")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AquaCalcApp() }
    }
}

@Composable
private fun AquaCalcApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? ComponentActivity
    val scope = rememberCoroutineScope()
    val alphaFlow = remember(activity) {
        activity?.dataStore?.data?.map { it[AlphaKey] ?: 0.22f }
    }
    val hapticsFlow = remember(activity) {
        activity?.dataStore?.data?.map { it[HapticsKey] ?: true }
    }
    val alpha by (alphaFlow?.collectAsState(initial = 0.22f) ?: remember { mutableStateOf(0.22f) })
    val haptics by (hapticsFlow?.collectAsState(initial = true) ?: remember { mutableStateOf(true) })
    val wallpaperColors = remember(context) {
        runCatching { android.app.WallpaperManager.getInstance(context).getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM) }.getOrNull()
    }
    val accent = wallpaperColors?.primaryColor?.let { Color(it.red(), it.green(), it.blue()) } ?: Color(0xFF75D8FF)
    val dark = Color(0xFF07111E)
    var display by remember { mutableStateOf("0") }
    var stored by remember { mutableStateOf("") }
    var op by remember { mutableStateOf<String?>(null) }
    var fresh by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<String>() }
    val view = LocalView.current

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent, secondary = accent.copy(alpha = 0.8f),
            background = dark, surface = Color(0xFF101E30),
            onBackground = Color(0xFFEAF5FF), onSurface = Color(0xFFEAF5FF)
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = dark) {
            Column(
                Modifier.fillMaxSize().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("AquaCalc", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                        Text("Стеклянный калькулятор", color = accent.copy(alpha = .9f), fontSize = 13.sp)
                    }
                    IconButton(onClick = { showHistory = !showHistory; showSettings = false }) {
                        Icon(Icons.Default.History, contentDescription = "История")
                    }
                    IconButton(onClick = { showSettings = !showSettings; showHistory = false }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                }

                if (showSettings) {
                    GlassPanel(alpha, accent) {
                        Text("Настройки", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("Прозрачность стекла: ${(alpha * 100).toInt()}%")
                        Slider(
                            value = alpha, onValueChange = { value ->
                                scope.launch { activity?.dataStore?.edit { it[AlphaKey] = value } }
                            }, valueRange = 0.08f..0.55f
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Тактильная вибрация", Modifier.weight(1f))
                            Switch(checked = haptics, onCheckedChange = { enabled ->
                                scope.launch { activity?.dataStore?.edit { it[HapticsKey] = enabled } }
                            })
                        }
                        Text("Цвет берётся из системных цветов обоев, если Android предоставляет его.", fontSize = 12.sp, color = Color.LightGray)
                    }
                } else if (showHistory) {
                    GlassPanel(alpha, accent) {
                        Text("История вычислений", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        if (history.isEmpty()) Text("Пока вычислений нет", color = Color.LightGray)
                        LazyColumn(Modifier.heightIn(max = 330.dp)) {
                            items(history.reversed()) { item -> Text(item, Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
                        }
                        TextButton(onClick = { history.clear() }) { Text("Очистить историю") }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalAlignment = Alignment.End) {
                        Text(if (stored.isNotEmpty() && op != null) "$stored $op" else " ", color = Color.LightGray, fontSize = 18.sp)
                        Text(display, fontSize = 48.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.End, maxLines = 2)
                    }
                    val rows = listOf(
                        listOf("AC", "±", "%", "÷"),
                        listOf("7", "8", "9", "×"),
                        listOf("4", "5", "6", "−"),
                        listOf("1", "2", "3", "+"),
                        listOf("⌫", "0", ".", "=")
                    )
                    rows.forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { label ->
                                val isOp = label in listOf("÷", "×", "−", "+", "=")
                                val isSpecial = label in listOf("AC", "±", "%", "⌫")
                                val buttonColor = when {
                                    label == "=" -> accent
                                    isOp -> accent.copy(alpha = .72f)
                                    isSpecial -> Color(0xFF30445D).copy(alpha = .65f)
                                    else -> Color(0xFFB9D9F2).copy(alpha = alpha)
                                }
                                Box(
                                    Modifier.weight(1f).height(62.dp)
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(buttonColor)
                                        .clickable {
                                            if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            when (label) {
                                                "AC" -> { display = "0"; stored = ""; op = null; fresh = false }
                                                "⌫" -> display = if (display.length > 1) display.dropLast(1) else "0"
                                                "±" -> if (display != "0") display = if (display.startsWith("-")) display.drop(1) else "-$display"
                                                "%" -> display = runCatching { (display.toDouble() / 100).toString().trimZeros() }.getOrDefault("0")
                                                "+", "−", "×", "÷" -> { stored = display; op = label; fresh = true }
                                                "=" -> {
                                                    val a = stored.toDoubleOrNull()
                                                    val b = display.toDoubleOrNull()
                                                    if (a != null && b != null && op != null) {
                                                        val result = runCatching {
                                                            when (op) {
                                                                "+" -> a + b
                                                                "−" -> a - b
                                                                "×" -> a * b
                                                                "÷" -> if (b == 0.0) Double.NaN else a / b
                                                                else -> b
                                                            }
                                                        }.getOrDefault(Double.NaN)
                                                        val pretty = if (result.isNaN() || result.isInfinite()) "Ошибка" else result.toString().trimZeros()
                                                        history.add("$stored $op $display = $pretty")
                                                        display = pretty; stored = ""; op = null; fresh = true
                                                    }
                                                }
                                                "." -> if (fresh) { display = "0."; fresh = false } else if (!display.contains(".")) display += "."
                                                else -> if (label.firstOrNull()?.isDigit() == true) {
                                                    display = if (fresh || display == "0" || display == "Ошибка") label else display + label
                                                    fresh = false
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label, fontSize = if (label.length > 1) 19.sp else 24.sp,
                                        color = if (label == "=") Color(0xFF061321) else Color.White,
                                        fontWeight = if (isOp) FontWeight.SemiBold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                    Text("Цветовая тема зависит от обоев устройства", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun GlassPanel(alpha: Float, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Color(0xFFB8D9F5).copy(alpha = alpha + 0.06f))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

private fun String.trimZeros(): String = runCatching {
    BigDecimal(this).stripTrailingZeros().toPlainString()
}.getOrDefault(this)
