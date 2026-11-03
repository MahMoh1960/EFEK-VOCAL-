package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.screens.MainStudioScreen
import com.example.ui.screens.MixerScreen
import com.example.ui.screens.RoutingScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.StudioTheme
import com.example.viewmodel.VocalStudioViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: VocalStudioViewModel by viewModels()

    // Standard Android Microphone permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Micro autorisé ! Vous pouvez activer le retour d'écoute.", Toast.LENGTH_SHORT).show()
            // Turn on processing dynamically if allowed
            viewModel.updateEffects { state -> state.eq.isEnabled = true }
        } else {
            Toast.makeText(this, "Autorisation refusée. Veuillez autoriser le micro pour traiter votre voix en temps réel.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            val currentTheme by viewModel.currentTheme.collectAsState()
            
            StudioTheme(theme = currentTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppScaffold(
                        viewModel = viewModel,
                        onRequestMicrophonePermission = { checkAndRequestMicPermission() }
                    )
                }
            }
        }
    }

    private fun checkAndRequestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Autorisation du micro déjà accordée !", Toast.LENGTH_SHORT).show()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@Composable
fun MainAppScaffold(
    viewModel: VocalStudioViewModel,
    onRequestMicrophonePermission: () -> Unit
) {
    val currentTab by viewModel.currentTab.collectAsState()
    val isInputActive by viewModel.isInputActive.collectAsState()
    
    val primaryColor = MaterialTheme.colorScheme.primary
    val context = LocalContext.current

    // Observe permission check
    val hasMicPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("app_scaffold"),
        bottomBar = {
            NavigationBar(
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .testTag("bottom_navigation_bar"),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                VocalStudioViewModel.StudioTab.values().forEach { tab ->
                    val isSelected = currentTab == tab
                    val icon = when (tab) {
                        VocalStudioViewModel.StudioTab.TRACKS -> Icons.Default.Build
                        VocalStudioViewModel.StudioTab.ROUTING -> Icons.Default.Share
                        VocalStudioViewModel.StudioTab.MIXER -> Icons.Default.Menu
                        VocalStudioViewModel.StudioTab.SETTINGS -> Icons.Default.Settings
                    }

                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { viewModel.selectTab(tab) },
                        icon = {
                            Icon(
                                imageVector = icon,
                                contentDescription = tab.label,
                                tint = if (isSelected) primaryColor else Color.Gray
                            )
                        },
                        label = {
                            Text(
                                text = tab.label,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Medium,
                                color = if (isSelected) primaryColor else Color.Gray,
                                maxLines = 1
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = primaryColor.copy(alpha = 0.12f)
                        ),
                        modifier = Modifier.testTag("nav_item_${tab.name.lowercase()}")
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // --- PROFESSIONAL PERMISSION WARNING CARD BANNER ---
            if (!hasMicPermission && isInputActive) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .border(1.dp, Color(0xFFFFCC00), RoundedCornerShape(8.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0x11FFCC00)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Avertissement micro",
                            tint = Color(0xFFFFCC00),
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Autorisation du micro requise",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Activez l'autorisation du micro pour enregistrer, traiter et écouter votre voix en temps réel.",
                                color = Color.LightGray,
                                fontSize = 10.sp,
                                lineHeight = 13.sp
                            )
                        }
                        Button(
                            onClick = onRequestMicrophonePermission,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFCC00)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("AUTORISER", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }

            // Animating Transitions between the 4 Screen Tabs
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (currentTab) {
                    VocalStudioViewModel.StudioTab.TRACKS -> MainStudioScreen(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                    VocalStudioViewModel.StudioTab.ROUTING -> RoutingScreen(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                    VocalStudioViewModel.StudioTab.MIXER -> MixerScreen(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                    VocalStudioViewModel.StudioTab.SETTINGS -> SettingsScreen(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
