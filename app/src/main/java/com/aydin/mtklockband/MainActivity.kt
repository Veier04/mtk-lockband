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
import android.location.LocationManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockBandScreen(apkPath: String) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Supported bands list
    val bandsList = remember {
        listOf(
            BandItem(1, "2100 MHz (XL, Tsel, Indosat, Tri)"),
            BandItem(3, "1800 MHz (XL, Tsel, Indosat, Tri)"),
            BandItem(5, "850 MHz (Smartfren, XL)"),
            BandItem(7, "2600 MHz (Future/Inter)"),
            BandItem(8, "900 MHz (XL, Tsel, Indosat)"),
            BandItem(20, "800 MHz (Rural Area)"),
            BandItem(28, "700 MHz (Tsel, Indosat - Digital Dividend)"),
            BandItem(38, "2600 MHz TDD"),
            BandItem(40, "2300 MHz TDD (Smartfren, Tsel)"),
            BandItem(41, "2500 MHz TDD (Indosat)")
        )
    }

    // UI Tab state
    var selectedTab by remember { mutableIntStateOf(0) }
    
    // Core parameters state
    var selectedBands by remember { mutableStateOf(setOf<Int>()) }
    var priorityBand by remember { mutableIntStateOf(0) }
    var selectedSlot by remember { mutableIntStateOf(0) } // 0 = SIM 1, 1 = SIM 2
    var ratMode by remember { mutableIntStateOf(33) } // 33 = Auto

    // Cell lock parameters UI state
    var mccInput by remember { mutableStateOf("") }
    var mncInput by remember { mutableStateOf("") }
    var earfcnInput by remember { mutableStateOf("") }
    var pciInput by remember { mutableStateOf("") }

    // Logs & Diagnostics state
    var consoleOutput by remember { mutableStateOf("Ready. Pilih Tab kuncian yang Anda inginkan.") }
    var atCommandInput by remember { mutableStateOf("") }
    val globalLog = remember { mutableStateListOf<String>("APP START OK") }
    var isOperating by remember { mutableStateOf(false) }
    
    // Realtime diagnostics state
    var currentInfo by remember { mutableStateOf(CellDiagnostics()) }
    var nearbyBandsList by remember { mutableStateOf(listOf<String>()) }
    val consoleScrollState = rememberScrollState()

    // Permission state
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        }
    )

    // Request permissions on launch
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE))
        }
    }

    // Diagnostics thread (every 2 seconds)
    LaunchedEffect(hasLocationPermission, selectedSlot) {
        while (true) {
            if (hasLocationPermission) {
                val diag = readCellDiagnostics(context, selectedSlot)
                currentInfo = diag
                nearbyBandsList = scanNearbyBands(context)
                
                // Prefill cell lock inputs from live network if empty
                if (mccInput.isEmpty() && diag.mcc.isNotEmpty()) mccInput = diag.mcc
                if (mncInput.isEmpty() && diag.mnc.isNotEmpty()) mncInput = diag.mnc
            }
            delay(2000)
        }
    }

    // Auto-scroll to bottom of console logs
    LaunchedEffect(consoleOutput) {
        consoleScrollState.animateScrollTo(consoleScrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Header
        Text(
            text = "MTK LOCK BAND",
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 2.dp)
        )
        Text(
            text = "MediaTek Developer Utility (AOSP) | by @B_ipul04",
            fontSize = 11.sp,
            color = Color.LightGray,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Custom TabRow (Material Design 3 Dark Style)
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text("MONITOR", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text("LOCK BAND", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                Text("LOCK CELL", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }) {
                Text("CONSOLE", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        // Active SIM Card bar (always visible below tabs)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("TARGET MODEM:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = { selectedSlot = 0 },
                    colors = ButtonDefaults.buttonColors(containerColor = if(selectedSlot == 0) MaterialTheme.colorScheme.primary else Color.Black),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Text("SIM 1", fontSize = 10.sp, color = Color.White)
                }
                Button(
                    onClick = { selectedSlot = 1 },
                    colors = ButtonDefaults.buttonColors(containerColor = if(selectedSlot == 1) MaterialTheme.colorScheme.primary else Color.Black),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Text("SIM 2", fontSize = 10.sp, color = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Toggle Views under Tabs
        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (selectedTab) {
                0 -> { // TAB 1: Live Monitor
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("SEL UTAMA AKTIF:", fontSize = 11.sp, color = Color.LightGray, fontWeight = FontWeight.Bold)
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = currentInfo.activeCellDisc,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF00FF00)
                                    )
                                    Text("PCI: ${currentInfo.pci}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                                Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color.DarkGray)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text("EARFCN: ${currentInfo.earfcn}", fontSize = 12.sp, color = Color.White)
                                        Text("MNC/MCC: ${currentInfo.mcc}-${currentInfo.mnc}", fontSize = 12.sp, color = Color.White)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("RSRP: ${currentInfo.rsrp} dBm", fontSize = 12.sp, color = Color.White)
                                        Text("SNR: ${currentInfo.snr} dB", fontSize = 12.sp, color = Color.White)
                                    }
                                }
                            }
                        }

                        // Scanned Area Bands list
                        Text(
                            text = "BAND YANG TERDETEKSI DI LOKASI ANDA (REALTIME):",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.LightGray,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (nearbyBandsList.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Memindai area sekitar... (Pastikan GPS menyala)", fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center)
                                }
                            } else {
                                LazyColumn(modifier = Modifier.padding(8.dp)) {
                                    items(nearbyBandsList) { item ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(item, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00FF00))
                                            Text("Tersedia", fontSize = 11.sp, color = Color.LightGray)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> { // TAB 2: Lock Band & RAT
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Priority dropdown select
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Primary CA Band Prioritas:", fontSize = 12.sp, color = Color.LightGray)
                            // Display selectable primary band dropdown
                            var expandedDropdown by remember { mutableStateOf(false) }
                            Box {
                                Button(
                                    onClick = { expandedDropdown = true },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(if (priorityBand == 0) "Tanpa Prioritas" else "LTE B$priorityBand", fontSize = 11.sp)
                                }
                                DropdownMenu(expanded = expandedDropdown, onDismissRequest = { expandedDropdown = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Tanpa Prioritas") },
                                        onClick = { priorityBand = 0; expandedDropdown = false }
                                    )
                                    selectedBands.forEach { b ->
                                        DropdownMenuItem(
                                            text = { Text("LTE Band $b") },
                                            onClick = { priorityBand = b; expandedDropdown = false }
                                        )
                                    }
                                }
                            }
                        }

                        // Network RAT selection layout
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Mode Jaringan (RAT):", fontSize = 12.sp, color = Color.LightGray)
                            var expandedRat by remember { mutableStateOf(false) }
                            Box {
                                Button(
                                    onClick = { expandedRat = true },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(getRatName(ratMode), fontSize = 11.sp)
                                }
                                DropdownMenu(expanded = expandedRat, onDismissRequest = { expandedRat = false }) {
                                    listOf(2 to "2G Only", 3 to "3G Only", 4 to "4G Only", 13 to "5G NR Only", 33 to "Auto (2G/3G/4G/5G)").forEach { item ->
                                        DropdownMenuItem(
                                            text = { Text(item.second) },
                                            onClick = { ratMode = item.first; expandedRat = false }
                                        )
                                    }
                                }
                            }
                        }

                        // Checklist band
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                .padding(4.dp)
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

                        Spacer(modifier = Modifier.height(8.dp))

                        // Controls buttons
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    if (selectedBands.isEmpty()) {
                                        consoleOutput = "Silakan pilih minimal 1 band terlebih dahulu."
                                        return@Button
                                    }
                                    isOperating = true; globalLog.add("[CMD] Executing action...")
                                    
                                    coroutineScope.launch {
                                        val bandsParam = selectedBands.joinToString(",")
                                        
                                        // 1. Lock bands & priority
                                        val lockResult = runRootCommand("lock", "$bandsParam $priorityBand $selectedSlot", apkPath)
                                        
                                        // 2. Lock network mode
                                        val ratResult = runRootCommand("rat", "$ratMode $selectedSlot", apkPath)
                                        
                                        consoleOutput = "KUNCI BAND:\n$lockResult\n------------------\nLOCK RAT:\n$ratResult"
                                        globalLog.add("[LOCK EXEC] BAND: $lockResult | RAT: $ratResult")
                                        isOperating = false
                                    }
                                },
                                enabled = !isOperating,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).padding(end = 4.dp)
                            ) {
                                Text("LOCK CONFIG")
                            }

                            Button(
                                onClick = {
                                    isOperating = true; globalLog.add("[CMD] Executing action...")
                                    consoleOutput = "Mereset modem ke status default..."
                                    coroutineScope.launch {
                                        val result = runRootCommand("reset", "$selectedSlot", apkPath)
                                        consoleOutput = result; globalLog.add("[RESULT]: $result")
                                        selectedBands = emptySet()
                                        priorityBand = 0
                                        ratMode = 33
                                        isOperating = false
                                    }
                                },
                                enabled = !isOperating,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).padding(start = 4.dp)
                            ) {
                                Text("RESET MODEM", color = Color.White)
                            }
                        }
                    }
                }
                2 -> { // TAB 3: Lock Cell Sector
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text("LOCK SEKTOR PEMANCAR SPESIFIK (CELL LOCK):", fontSize = 12.sp, color = Color.LightGray, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = mccInput,
                            onValueChange = { mccInput = it },
                            label = { Text("MCC (Mobile Country Code - e.g. 510)") },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = mncInput,
                            onValueChange = { mncInput = it },
                            label = { Text("MNC (Mobile Network Code - e.g. 11)") },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = earfcnInput,
                            onValueChange = { earfcnInput = it },
                            label = { Text("EARFCN (Frequency Channel)") },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = pciInput,
                            onValueChange = { pciInput = it },
                            label = { Text("PCI (Physical Cell Identifier)") },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            singleLine = true
                        )

                        Row(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    val earfcn = earfcnInput.toIntOrNull()
                                    val pci = pciInput.toIntOrNull()
                                    if (mccInput.isEmpty() || mncInput.isEmpty() || earfcn == null || pci == null) {
                                        consoleOutput = "ERROR: Parameter input MCC/MNC/EARFCN/PCI tidak boleh kosong."
                                        return@Button
                                    }
                                    isOperating = true; globalLog.add("[CMD] Executing action...")
                                    consoleOutput = "Mengunci pemancar target..."
                                    coroutineScope.launch {
                                        val result = runRootCommand("lock_cell", "$mccInput $mncInput $earfcn $pci $selectedSlot", apkPath)
                                        consoleOutput = result; globalLog.add("[RESULT]: $result")
                                        isOperating = false
                                    }
                                },
                                enabled = !isOperating,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).padding(end = 4.dp)
                            ) {
                                Text("LOCK SECTOR")
                            }

                            Button(
                                onClick = {
                                    val earfcn = earfcnInput.toIntOrNull() ?: 0
                                    val pci = pciInput.toIntOrNull() ?: 0
                                    isOperating = true; globalLog.add("[CMD] Executing action...")
                                    consoleOutput = "Membuka kunci pemancar..."
                                    coroutineScope.launch {
                                        val result = runRootCommand("unlock_cell", "$mccInput $mncInput $earfcn $pci $selectedSlot", apkPath)
                                        consoleOutput = result; globalLog.add("[RESULT]: $result")
                                        isOperating = false
                                    }
                                },
                                enabled = !isOperating,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).padding(start = 4.dp)
                            ) {
                                Text("RELEASE LOCK", color = Color.White)
                            }
                        }
                    }
                }
                3 -> { // TAB 4: Logs Console
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(Color.Black, RoundedCornerShape(12.dp))
                                .padding(8.dp)
                        ) {
                            SelectionContainer {
                                Text(
                                    text = globalLog.joinToString("\n"),
                                    color = Color(0xFF00FF00),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(consoleScrollState),
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = atCommandInput,
                                onValueChange = { atCommandInput = it },
                                placeholder = { Text("Ketik command, cth: AT+ECA?") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (atCommandInput.isNotEmpty()) {
                                        val cmd = atCommandInput
                                        atCommandInput = ""
                                        isOperating = true
                                        globalLog.add("[AT_TX]: $cmd")
                                        coroutineScope.launch {
                                            val res = runRootCommand("raw", cmd, apkPath)
                                            globalLog.add("[AT_RX]: $res")
                                            isOperating = false
                                        }
                                    }
                                },
                                enabled = !isOperating,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("SEND")
                            }
                        }
                    }
                }
            }
        }
    }
}

// Convert Network Mode ID to Name String
private fun getRatName(mode: Int): String {
    return when (mode) {
        2 -> "2G GSM Only"
        3 -> "3G WCDMA Only"
        4 -> "4G LTE Only"
        13 -> "5G NR Only"
        33 -> "Auto (2G/3G/4G/5G)"
        else -> "RAT ID: $mode"
    }
}

data class CellDiagnostics(
    val activeCellDisc: String = "NO SERVICE",
    val earfcn: Int = 0,
    val pci: Int = 0,
    val mcc: String = "",
    val mnc: String = "",
    val rsrp: Int = 0,
    val snr: Int = 0
)

// Scans active cells and fills metrics
private fun readCellDiagnostics(context: Context, slotIndex: Int): CellDiagnostics {
    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return CellDiagnostics()
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        return CellDiagnostics(activeCellDisc = "Butuh izin Lokasi")
    }

    
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
        return CellDiagnostics(activeCellDisc = "GPS MATI (Wajib Aktif)")
    }
    val cellInfos = tm.allCellInfo ?: return CellDiagnostics()
    for (info in cellInfos) {
        if (info.isRegistered) {
            if (info is CellInfoLte) {
                val dbm = info.cellSignalStrength.rsrp
                val snr = info.cellSignalStrength.rssnr
                val identity = info.cellIdentity
                val band = if (android.os.Build.VERSION.SDK_INT >= 30) {
                    identity.bands.firstOrNull() ?: 3
                } else {
                    getLteBandFromEarfcn(identity.earfcn)
                }
                
                return CellDiagnostics(
                    activeCellDisc = "XL 4G • LTE $band",
                    earfcn = identity.earfcn,
                    pci = identity.pci,
                    mcc = identity.mccString ?: "",
                    mnc = identity.mncString ?: "",
                    rsrp = dbm,
                    snr = snr
                )
            } else if (info is CellInfoNr) {
                val identity = info.cellIdentity as CellIdentityNr
                val dbm = info.cellSignalStrength.dbm
                val bands = if (android.os.Build.VERSION.SDK_INT >= 30) {
                    identity.bands.firstOrNull() ?: 40
                } else {
                    getNrBandFromNrarfcn(identity.nrarfcn)
                }
                
                return CellDiagnostics(
                    activeCellDisc = "XL 5G • NR n$bands",
                    earfcn = identity.nrarfcn,
                    pci = identity.pci,
                    mcc = identity.mccString ?: "",
                    mnc = identity.mncString ?: "",
                    rsrp = dbm,
                    snr = 0
                )
            }
        }
    }
    return CellDiagnostics()
}

// Scans surrounding nearby sectors and collects unique bands
private fun scanNearbyBands(context: Context): List<String> {
    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return emptyList()
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        return emptyList()
    }
    
    val cellInfos = tm.allCellInfo ?: return emptyList()
    val bandsSet = mutableSetOf<String>()
    
    for (info in cellInfos) {
        if (info is CellInfoLte) {
            val identity = info.cellIdentity
            val band = if (android.os.Build.VERSION.SDK_INT >= 30) {
                identity.bands.joinToString(", ") { "LTE Band $it" }
            } else {
                "LTE Band ${getLteBandFromEarfcn(identity.earfcn)}"
            }
            bandsSet.add(band)
        } else if (info is CellInfoNr) {
            val identity = info.cellIdentity as CellIdentityNr
            val band = if (android.os.Build.VERSION.SDK_INT >= 30) {
                identity.bands.joinToString(", ") { "5G NR n$it" }
            } else {
                "5G NR n${getNrBandFromNrarfcn(identity.nrarfcn)}"
            }
            bandsSet.add(band)
        }
    }
    return bandsSet.toList().sorted()
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
            "Selesai tanpa output."
        } else {
            output.toString()
        }
    } catch (e: Exception) {
        "Gagal mengeksekusi shell root:\n${e.message}"
    }
}
