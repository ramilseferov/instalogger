package com.instalogger.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.service.BatteryHelper
import com.instalogger.app.service.InstaLoggerForegroundService
import com.instalogger.app.ui.components.InstaLoggerTopBar
import com.instalogger.app.ui.components.StatusBadge
import com.instalogger.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as InstaLoggerApp
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isBatteryIgnored by remember { mutableStateOf(BatteryHelper.isBatteryOptimizationIgnored(context)) }

    Scaffold(
        topBar = {
            InstaLoggerTopBar(
                title = "Tənzimləmələr",
                subtitle = "Müstəqil mühərrik və sistem konfiqurasiyası"
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Standalone Native Engine Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = "Müstəqil Daxili Mühərrik",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        StatusBadge(
                            text = "Aktivdir",
                            icon = Icons.Default.CheckCircle,
                            containerColor = StatusGreenLight,
                            contentColor = StatusGreen
                        )
                    }

                    Text(
                        text = "Tətbiq heç bir xarici daemon və ya serverə ehtiyac duymadan birbaşa cihaz daxilində tam müstəqil çalışır.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Battery Optimization Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = "Batareya Optimizasiyası",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (isBatteryIgnored) {
                            StatusBadge(
                                text = "Məhdudiyyətsiz",
                                icon = Icons.Default.CheckCircle,
                                containerColor = StatusGreenLight,
                                contentColor = StatusGreen
                            )
                        } else {
                            StatusBadge(
                                text = "Məhdud",
                                icon = Icons.Default.Warning,
                                containerColor = StatusAmberLight,
                                contentColor = StatusAmber
                            )
                        }
                    }

                    Text(
                        text = "Arxa planda fasiləsiz işləmək və anlıq bildiriş almaq üçün sistem batareya qoruyucusundan istisna tələb edin.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = {
                            BatteryHelper.requestIgnoreBatteryOptimization(context)
                            isBatteryIgnored = BatteryHelper.isBatteryOptimizationIgnored(context)
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("İstisna Tələb Et")
                    }
                }
            }

            // Foreground Service Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Arxa Plan Xidməti",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Bildiriş panelində daimi xidmət statusunu təzələmək üçün xidməti yenidən başladın.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedButton(
                        onClick = {
                            InstaLoggerForegroundService.start(context)
                            scope.launch {
                                snackbarHostState.showSnackbar("Arxa fon xidməti yenidən başladıldı")
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Xidməti Yenidən Başlat")
                    }
                }
            }

            // Local Data Clean Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Məlumatların Təmizlənməsi",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Cihazda saxlanılan bütün yerli hədəf, bildiriş və arxiv qeydlərini sıfırlayın.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    app.database.targetDao().clearAll()
                                    app.database.changeLogDao().clearAll()
                                    app.database.avatarHistoryDao().clearAll()
                                    app.database.notificationDao().clearAll()
                                }
                                snackbarHostState.showSnackbar("Bütün yerli qeydlər sıfırlandı")
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRed)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Bütün Məlumatları Sıfırla")
                    }
                }
            }
        }
    }
}
