package com.aydin.mtklockband

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF0052D4),
                    background = Color(0xFF0F172A),
                    surface = Color(0xFF1E293B)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LockBandScreen(apkPath = packageCodePath)
                }
            }
        }
    }
}

@Composable
fun LockBandScreen(apkPath: String) {
    val coroutineScope = rememberCoroutineScope()
    
    // Standard Indonesian LTE bands
    val bandsList = remember {
        listOf(
            BandItem(1, "2100 MHz (XL, Tsel, Indosat, Tri)"),
            BandItem(3, "1800 MHz (XL, Tsel, Indosat, Tri)"),
            BandItem(5, "850 MHz (Smartfren)"),
            BandItem(8, "900 MHz (XL, Tsel, Indosat)"),
            BandItem(40, "2300 MHz (Smartfren, Tsel)")
        )
    }

    var selectedBands by remember { mutableStateOf(setOf<Int>()) }
    var consoleOutput by remember { mutableStateOf("Ready. Status Root belum dicek.") }
    var isOperating by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "MTK LOCK BAND",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "AOSP Universal Band Selection (Root Required)",
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Lazy check box list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(8.dp)
        ) {
            items(bandsList) { band ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = selectedBands.contains(band.id),
                        onCheckedChange = { checked ->
                            selectedBands = if (checked) {
                                selectedBands + band.id
                            } else {
                                selectedBands - band.id
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "LTE Band ${band.id}",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = band.description,
                            fontSize = 12.sp,
                            color = Color.LightGray
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Console logger
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(Color.Black, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            Text(
                text = consoleOutput,
                color = Color(0xFF00FF00),
                modifier = Modifier.fillMaxSize(),
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = {
                    if (selectedBands.isEmpty()) {
                        consoleOutput = "Silakan pilih minimal 1 band terlebih dahulu."
                        return@Button
                    }
                    isOperating = true
                    consoleOutput = "Mengunci band..."
                    coroutineScope.launch {
                        val bandsParam = selectedBands.joinToString(",")
                        val result = runRootCommand("lock", bandsParam, apkPath)
                        consoleOutput = result
                        isOperating = false
                    }
                },
                enabled = !isOperating,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) {
                Text("LOCK SELECT")
            }

            Button(
                onClick = {
                    isOperating = true
                    consoleOutput = "Mereset ke default..."
                    coroutineScope.launch {
                        val result = runRootCommand("reset", "", apkPath)
                        consoleOutput = result
                        selectedBands = emptySet()
                        isOperating = false
                    }
                },
                enabled = !isOperating,
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            ) {
                Text("RESET DEFAULT", color = Color.White)
            }
        }
    }
}

data class BandItem(val id: Int, val description: String)

private suspend fun runRootCommand(cmd: String, args: String, apkPath: String): String = withContext(Dispatchers.IO) {
    val shellCommand = "su -c \"settings put global hidden_api_policy 1 && export CLASSPATH=$apkPath && exec app_process / com.aydin.mtklockband.RootHelper $cmd $args\""
    val output = StringBuilder()
    try {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", shellCommand))
        
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val errorReader = BufferedReader(InputStreamReader(process.errorStream))
        
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            output.append(line).append("\n")
        }
        while (errorReader.readLine().also { line = it } != null) {
            output.append("ERROR: ").append(line).append("\n")
        }
        
        process.waitFor()
        if (output.isEmpty()) {
            "Selesai tanpa output (Apakah HP sudah ROOT & izin diberikan?)"
        } else {
            output.toString()
        }
    } catch (e: Exception) {
        "Gagal mengeksekusi shell root:\n${e.message}"
    }
}
