package com.geeksville.mesh.convoy

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun ConvoyTransferRideScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val today   = LocalDate.now()
    val fmt     = DateTimeFormatter.ISO_LOCAL_DATE

    val allRides = remember {
        ConvoyEventStore.loadAll(context)
            .sortedWith(
                compareByDescending<ConvoyEventConfig> {
                    try { LocalDate.parse(it.eventDate, fmt) >= today } catch (e: Exception) { false }
                }.thenByDescending {
                    try { LocalDate.parse(it.eventDate, fmt) } catch (e: Exception) { LocalDate.MIN }
                }
            )
    }

    var searchQuery  by remember { mutableStateOf("") }
    var selectedRide by remember { mutableStateOf<ConvoyEventConfig?>(null) }
    var statusMsg    by remember { mutableStateOf("") }

    val filteredRides = remember(searchQuery, allRides) {
        if (searchQuery.isBlank()) allRides
        else allRides.filter { ride ->
            "${ride.organizerFirstName} ${ride.organizerLastName}"
                .contains(searchQuery, ignoreCase = true)
        }
    }

    fun sendRideEmail(ride: ConvoyEventConfig) {
        try {
            val safeName = ride.eventName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val safeDate = ride.eventDate.replace("/", "-").replace(" ", "_")
            val fileName = "convoy_ride_${safeName}_${safeDate}.json"
            val outFile  = File(context.cacheDir, fileName)
            outFile.writeText(ride.toJson().toString(2))

            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.provider", outFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_SUBJECT, "Convoy Ride: ${ride.eventName} — ${ride.eventDate}")
                putExtra(Intent.EXTRA_TEXT,
                    "You have been invited to join a convoy ride.\n\n" +
                    "Ride: ${ride.eventName}\n" +
                    "Date: ${ride.eventDate}\n" +
                    "Organizer: ${ride.organizerFirstName} ${ride.organizerLastName}\n\n" +
                    "Open the attached file with the Convoy app to import this ride.")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Send Ride via..."))
            statusMsg = "\u2713 Share sheet opened"
            Log.i("ConvoyTransfer", "Sharing ride: ${ride.eventName}")
        } catch (e: Exception) {
            statusMsg = "\u2717 Failed: ${e.message}"
            Log.e("ConvoyTransfer", "Share failed: ${e.message}")
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101510))) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {

            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("\u2190", color = Color(0xFF97D5A5), fontSize = 20.sp,
                    modifier = Modifier.clickable { onBack() }.padding(end = 12.dp))
                Text("TRANSFER RIDE", color = Color(0xFF97D5A5), fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp)
            }
            Spacer(Modifier.height(8.dp))

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it; selectedRide = null },
                placeholder = { Text("Search by organizer name...", fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, color = Color(0xFF8B938A)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))

            if (allRides.isEmpty()) {
                Text("No rides found. Create a ride first.",
                    color = Color(0xFF8B938A), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(8.dp))
            } else {
                // Column headers
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                    Text("", modifier = Modifier.width(20.dp))
                    Text("RIDE NAME", color = Color(0xFF4A6080), fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.weight(2f))
                    Text("DATE", color = Color(0xFF4A6080), fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    Text("ORGANIZER", color = Color(0xFF4A6080), fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1.5f))
                    Text("CH", color = Color(0xFF4A6080), fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filteredRides) { ride ->
                        val isSelected = selectedRide?.eventId == ride.eventId
                        val rideDate   = try { LocalDate.parse(ride.eventDate, fmt) }
                                         catch (e: Exception) { LocalDate.MIN }
                        val isFuture   = rideDate >= today
                        val organizer  = "${ride.organizerFirstName} ${ride.organizerLastName}"

                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                .clickable { selectedRide = if (isSelected) null else ride },
                            shape = RoundedCornerShape(6.dp),
                            color = when {
                                isSelected -> Color(0xFF1F4E79)
                                isFuture   -> Color(0xFF1C211C)
                                else       -> Color(0xFF161A16)
                            }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Selector
                                Text(
                                    if (isSelected) "\u2713" else if (isFuture) "\u25cf" else "\u25cb",
                                    color = if (isSelected) Color(0xFF4DA6FF)
                                            else if (isFuture) Color(0xFF97D5A5)
                                            else Color(0xFF4A6080),
                                    fontSize = 10.sp, modifier = Modifier.width(20.dp)
                                )
                                // Ride name
                                Text(ride.eventName,
                                    color = if (isSelected) Color.White
                                            else if (isFuture) Color(0xFFDFE4DC)
                                            else Color(0xFF8B938A),
                                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(2f))
                                // Date
                                Text(ride.eventDate,
                                    color = if (isFuture) Color(0xFF97D5A5) else Color(0xFF4A6080),
                                    fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f))
                                // Organizer
                                Text(organizer,
                                    color = Color(0xFF8B938A), fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1.5f))
                                // Channel
                                Text(ride.channelName,
                                    color = Color(0xFF4A6080), fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                // Status
                if (statusMsg.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp),
                        color = if (statusMsg.startsWith("\u2713")) Color(0xFF0D2010)
                                else Color(0xFF2A1A1A)) {
                        Text(statusMsg,
                            color = if (statusMsg.startsWith("\u2713")) Color(0xFF97D5A5)
                                    else Color(0xFFFFB4AB),
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(8.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Send button
                Surface(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = selectedRide != null) {
                            selectedRide?.let { sendRideEmail(it) }
                        },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedRide != null) Color(0xFF1F4E79) else Color(0xFF1C211C)
                ) {
                    Text(
                        text = if (selectedRide != null)
                            "\u2709 SEND ${selectedRide!!.eventName.uppercase()} VIA EMAIL"
                            else "SELECT A RIDE TO SEND",
                        color = if (selectedRide != null) Color.White else Color(0xFF8B938A),
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 14.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onBack() },
                    shape = RoundedCornerShape(10.dp), color = Color(0xFF2A1A1A)
                ) {
                    Text("CANCEL", color = Color(0xFFFFB4AB), fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 14.dp))
                }
            }
        }
    }
}
