package com.aydin.mtklockband

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellIdentityNr
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Full Indonesian LTE bands
    val bandsList = remember {
        listOf(
            BandItem(1, "2100 MHz (XL, Tsel, Indosat, Tri)"),
            BandItem(3, "1800 MHz (XL, Tsel, Indosat, Tri)"),
            BandItem(5, "850 MHz (Smartfren, XL)"),
            BandItem(7, "2600 MHz (Inter/Future Band)"),
            BandItem(8, "900 MHz (XL, Tsel, Indosat)"),
            BandItem(20, "800 MHz (Rural Area)"),
            BandItem(28, "700 MHz (Telkomsel, Indosat - Digital Dividend)"),
            BandItem(38, "2600 MHz TDD"),
            BandItem(40, "2300 MHz TDD (Smartfren, Tsel)"),
            BandItem(41, "2500 MHz TDD (Indosat)")
        )
    }

    var selectedBands by remember { mutableStateOf(setOf<Int>()) }
    var selectedSlot by remember { mutableIntStateOf(0) } // 0 = SIM 1, 1 = SIM 2
    var consoleOutput by remember { mutableStateOf("Ready. Pilih SIM Card dan Band untuk dikunci.") }
    var isOperating by remember { mutableStateOf(false) }
    var activeBandInfo by remember { mutableStateOf("Membaca sinyal...") }
    val consoleScrollState = rememberScrollState()

    // Permission state
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasLocationPermission = isGranted
        }
    )

    // Request location permission (required to read active cell details on Android)
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // Monitor active band (updates every 2 seconds)
    LaunchedEffect(hasLocationPermission, selectedSlot) {
        while (true) {
            if (hasLocationPermission) {
                activeBandInfo = getActiveCellBand(context, selectedSlot)
            } else {
                activeBandInfo = "Butuh izin Lokasi untuk info band."
            }
            delay(2000)
        }
    }

    // Auto-scroll to bottom when console text updates
    LaunchedEffect(consoleOutput) {
        consoleScrollState.animateScrollTo(consoleScrollState.maxValue)
    }

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
            modifier = Modifier.padding(bottom = 2.dp)
        )

        Text(
            text = "AOSP Universal Band Selection (Root Required)",
            fontSize = 11.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Live Active Sinyal Monitor
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = "INFO SINYAL AKTIF saat ini:",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.LightGray
                )
                Text(
                    text = activeBandInfo,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF00FF00),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        // SIM card slot selector
        Text(
            text = "PILIH SLOT SIM CARD:",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.LightGray,
            modifier = Modifier.align(Alignment.Start).padding(bottom = 4.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { selectedSlot = 0 },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedSlot == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "SIM 1 (Slot 0)", 
                    color = if (selectedSlot == 0) Color.White else Color.LightGray,
                    fontSize = 12.sp
                )
            }
            Button(
                onClick = { selectedSlot = 1 },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedSlot == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "SIM 2 (Slot 1)", 
                    color = if (selectedSlot == 1) Color.White else Color.LightGray,
                    fontSize = 12.sp
                )
            }
        }

        // Checklist band
        Text(
            text = "PILIH BAND LTE YANG INGIN DIAKTIFKAN:",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.LightGray,
            modifier = Modifier.align(Alignment.Start).padding(bottom = 4.dp)
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(6.dp)
        ) {
            items(bandsList) { band ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp),
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
                            color = Color.White,
                            fontSize = 14.sp
                        )
                        Text(
                            text = band.description,
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Console logger (Scrollable & Selection/Copy)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(Color.Black, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            SelectionContainer {
                Text(
                    text = consoleOutput,
                    color = Color(0xFF00FF00),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(consoleScrollState),
                    fontSize = 10.sp,
                    lineHeight = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

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
                        val result = runRootCommand("lock", "$bandsParam $selectedSlot", apkPath)
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
                        val result = runRootCommand("reset", "$selectedSlot", apkPath)
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

// Read active bands via AOSP CellInfo API
private fun getActiveCellBand(context: Context, slotIndex: Int): String {
    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return "Telephony Service tidak aktif."
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        return "Izin lokasi tidak diberikan."
    }
    
    val cellInfos = tm.allCellInfo
    if (cellInfos.isNullOrEmpty()) {
        return "Sinyal Kosong / Sel tidak terdeteksi."
    }

    val activeBands = mutableListOf<String>()
    
    for (info in cellInfos) {
        if (info.isRegistered) { // Only read registered cell tower
            if (info is CellInfoLte) {
                val cellIdentity = info.cellIdentity
                val bands = if (android.os.Build.VERSION.SDK_INT >= 30) {
                    cellIdentity.bands.joinToString(", ") { "B$it" }
                } else {
                    "B${getLteBandFromEarfcn(cellIdentity.earfcn)}"
                }
                activeBands.add("LTE $bands (EARFCN: ${cellIdentity.earfcn})")
            } else if (info is CellInfoNr) {
                val cellIdentity = info.cellIdentity as CellIdentityNr
                val nrarfcn = cellIdentity.nrarfcn
                val bands = if (android.os.Build.VERSION.SDK_INT >= 30) {
                    try {
                        cellIdentity.bands.joinToString(", ") { "n$it" }
                    } catch (e: Exception) {
                        "n${getNrBandFromNrarfcn(nrarfcn)}"
                    }
                } else {
                    "n${getNrBandFromNrarfcn(nrarfcn)}"
                }
                activeBands.add("5G $bands (NR-ARFCN: $nrarfcn)")
            }
        }
    }

    return if (activeBands.isNotEmpty()) {
        activeBands.joinToString(" | ")
    } else {
        "Tidak terhubung ke sel seluler"
    }
}

// Fallback logic to get LTE Band from Earfcn
private fun getLteBandFromEarfcn(earfcn: Int): Int {
    return when (earfcn) {
        in 0..599 -> 1
        in 600..1199 -> 2
        in 1200..1949 -> 3
        in 1950..2399 -> 4
        in 2400..2649 -> 5
        in 2750..3449 -> 7
        in 3450..3799 -> 8
        in 6150..6599 -> 20
        in 9210..9659 -> 28
        in 37750..38249 -> 38
        in 38650..39449 -> 40
        in 39650..41589 -> 41
        else -> 0
    }
}

// Fallback logic to get NR Band from Nrarfcn
private fun getNrBandFromNrarfcn(nrarfcn: Int): Int {
    return when (nrarfcn) {
        in 422000..434000 -> 1
        in 360000..380000 -> 3
        in 173900..178100 -> 8
        in 460000..480000 -> 40
        in 620000..653333 -> 78
        else -> 0
    }
}

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
            "Selesai tanpa output."
        } else {
            output.toString()
        }
    } catch (e: Exception) {
        "Gagal mengeksekusi shell root:\n${e.message}"
    }
}
