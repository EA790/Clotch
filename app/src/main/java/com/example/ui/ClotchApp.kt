package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.example.R
import com.example.ui.components.DiscordSignInDialog
import com.example.ui.screens.CaptureScreen
import com.example.ui.screens.ClipsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.PresenceScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ClotchBlue
import com.example.ui.theme.ClotchBlueMuted
import com.example.ui.theme.DiscordBlurple
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateCard
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

enum class ClotchRoute(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    Home("home", "Home", Icons.Filled.Home),
    Presence("presence", "Presence", Icons.AutoMirrored.Filled.FormatListBulleted),
    Recording("recording", "Recording", Icons.Filled.FiberManualRecord),
    Clips("clips", "Clips", Icons.Filled.VideoLibrary),
    Settings("settings", "Settings", Icons.Filled.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClotchApp(
    quickStartTriggerCount: Int = 0,
    oauthAccessToken: String? = null,
    onOAuthTokenConsumed: () -> Unit = {},
    viewModel: ClotchViewModel = viewModel()
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ClotchRoute.Home.route

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val recorderState by viewModel.recorderState.collectAsStateWithLifecycle()
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    val discordState by viewModel.discordConnectionState.collectAsStateWithLifecycle()
    val discordPresence by viewModel.discordPresence.collectAsStateWithLifecycle()
    val discordProfile by viewModel.discordProfile.collectAsStateWithLifecycle()
    val oauthClientId by viewModel.oauthClientId.collectAsStateWithLifecycle()
    val isFetchingProfile by viewModel.isFetchingProfile.collectAsStateWithLifecycle()

    var showSignInDialog by remember { mutableStateOf(false) }
    var pendingProjectionLaunchAfterNotif by remember { mutableStateOf(false) }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.onMediaProjectionApproved(result.resultCode, result.data!!)
        } else {
            viewModel.onMediaProjectionDenied()
        }
    }

    fun launchProjectionRequest() {
        val projectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.syncIdleNotification()
        } else {
            viewModel.showToast("Notification permission denied; recording controls won't appear in shade")
        }
        if (pendingProjectionLaunchAfterNotif) {
            pendingProjectionLaunchAfterNotif = false
            viewModel.startCountdownAndCapture { launchProjectionRequest() }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.setRecordMicAudio(true)
        } else {
            viewModel.setRecordMicAudio(false)
            viewModel.showToast("Microphone permission denied; recording video only")
        }
    }

    fun requestStartCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotif = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotif) {
                pendingProjectionLaunchAfterNotif = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        viewModel.startCountdownAndCapture { launchProjectionRequest() }
    }

    fun navigateTo(route: ClotchRoute) {
        navController.navigate(route.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        coroutineScope.launch { drawerState.close() }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotif = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (hasNotif) {
                viewModel.syncIdleNotification()
            }
        } else {
            viewModel.syncIdleNotification()
        }
    }

    LaunchedEffect(quickStartTriggerCount) {
        if (quickStartTriggerCount > 0) {
            navigateTo(ClotchRoute.Recording)
            requestStartCapture()
        }
    }

    LaunchedEffect(oauthAccessToken) {
        if (!oauthAccessToken.isNullOrBlank()) {
            viewModel.handleOAuthAccessToken(oauthAccessToken)
            onOAuthTokenConsumed()
            showSignInDialog = false
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = CarbonSurface,
                drawerContentColor = TextPrimary
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (discordProfile.isSignedIn) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(SlateCard)
                                .clickable { navigateTo(ClotchRoute.Presence) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AsyncImage(
                                model = discordProfile.avatarUrl,
                                contentDescription = "Discord avatar",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .border(1.5.dp, DiscordBlurple, CircleShape)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = discordProfile.displayTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "@${discordProfile.username}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_clotch_mascot),
                                contentDescription = "Clotch Mascot",
                                modifier = Modifier.size(44.dp),
                                contentScale = ContentScale.Fit
                            )
                            Column {
                                Text(
                                    text = "Clotch",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Did You Clip that \"CLOTCH\"?",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = HairlineBorder)
                Spacer(modifier = Modifier.height(8.dp))

                ClotchRoute.entries.forEach { dest ->
                    val selected = currentRoute == dest.route
                    NavigationDrawerItem(
                        label = { Text(dest.label) },
                        selected = selected,
                        onClick = { navigateTo(dest) },
                        icon = {
                            Icon(
                                imageVector = dest.icon,
                                contentDescription = dest.label
                            )
                        },
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .testTag("drawer_${dest.route}"),
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = ClotchBlueMuted,
                            selectedIconColor = ClotchBlue,
                            selectedTextColor = TextPrimary,
                            unselectedContainerColor = Color.Transparent,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        )
                    )
                }
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = ObsidianBg,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Clotch",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { coroutineScope.launch { drawerState.open() } },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("drawer_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = "Open navigation menu",
                                tint = TextPrimary
                            )
                        }
                    },
                    actions = {
                        if (discordProfile.isSignedIn) {
                            Box(
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .border(1.5.dp, DiscordBlurple, CircleShape)
                                    .clickable { navigateTo(ClotchRoute.Presence) }
                                    .testTag("topbar_discord_avatar")
                            ) {
                                AsyncImage(
                                    model = discordProfile.avatarUrl,
                                    contentDescription = "Signed in Discord profile",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            Surface(
                                onClick = { showSignInDialog = true },
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .testTag("topbar_sign_in_pill"),
                                color = SlateCard,
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = "Sign In",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = { navigateTo(ClotchRoute.Settings) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("topbar_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Settings",
                                tint = TextSecondary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = ObsidianBg,
                        titleContentColor = TextPrimary,
                        navigationIconContentColor = TextPrimary
                    )
                )
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = ClotchRoute.Home.route,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                composable(ClotchRoute.Home.route) {
                    HomeScreen(
                        settings = settings,
                        recorderState = recorderState,
                        discordState = discordState,
                        clipsCount = clips.size,
                        onOpenPresence = { navigateTo(ClotchRoute.Presence) },
                        onOpenRecording = { navigateTo(ClotchRoute.Recording) },
                        onOpenClips = { navigateTo(ClotchRoute.Clips) }
                    )
                }

                composable(ClotchRoute.Presence.route) {
                    PresenceScreen(
                        settings = settings,
                        recorderState = recorderState,
                        discordState = discordState,
                        discordPresence = discordPresence,
                        discordProfile = discordProfile,
                        isFetchingProfile = isFetchingProfile,
                        onGameNameChanged = { viewModel.setGameName(it) },
                        onOpenSignInDialog = { showSignInDialog = true },
                        onSignOutDiscord = { viewModel.signOutDiscord() },
                        onConnectRpc = { viewModel.connectDiscordRpc() },
                        onDisconnectRpc = { viewModel.disconnectDiscordRpc() }
                    )
                }

                composable(ClotchRoute.Recording.route) {
                    CaptureScreen(
                        settings = settings,
                        recorderState = recorderState,
                        onStartCapture = { requestStartCapture() },
                        onStopCapture = { viewModel.stopRecording() },
                        onPauseCapture = { viewModel.pauseRecording() },
                        onResumeCapture = { viewModel.resumeRecording() },
                        onCancelCountdown = { viewModel.cancelCountdown() },
                        onToggleQuickNotification = { enabled ->
                            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val hasNotif = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasNotif) {
                                    notificationPermissionLauncher.launch(
                                        Manifest.permission.POST_NOTIFICATIONS
                                    )
                                }
                            }
                            viewModel.setShowQuickNotification(enabled)
                        },
                        onOpenSettings = { navigateTo(ClotchRoute.Settings) },
                        onOpenClips = { navigateTo(ClotchRoute.Clips) }
                    )
                }

                composable(ClotchRoute.Clips.route) {
                    ClipsScreen(
                        clips = clips,
                        onRefreshClips = { viewModel.refreshClips() },
                        onDeleteClip = { viewModel.deleteClip(it) },
                        onShowMessage = { viewModel.showToast(it) }
                    )
                }

                composable(ClotchRoute.Settings.route) {
                    SettingsScreen(
                        settings = settings,
                        discordState = discordState,
                        discordProfile = discordProfile,
                        isFetchingProfile = isFetchingProfile,
                        onSelectResolution = { viewModel.setResolution(it) },
                        onSelectFps = { viewModel.setFps(it) },
                        onSelectBitrate = { viewModel.setBitrate(it) },
                        onSelectOrientation = { viewModel.setOrientation(it) },
                        onSelectCountdown = { viewModel.setCountdown(it) },
                        onToggleMicAudio = { enabled ->
                            if (enabled) {
                                val hasMic = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasMic) {
                                    viewModel.setRecordMicAudio(true)
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            } else {
                                viewModel.setRecordMicAudio(false)
                            }
                        },
                        onToggleQuickNotification = { enabled ->
                            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val hasNotif = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasNotif) {
                                    notificationPermissionLauncher.launch(
                                        Manifest.permission.POST_NOTIFICATIONS
                                    )
                                }
                            }
                            viewModel.setShowQuickNotification(enabled)
                        },
                        onToggleDiscordRpc = { viewModel.setDiscordRpcEnabled(it) },
                        onGameNameChanged = { viewModel.setGameName(it) },
                        onDiscordTokenChanged = { viewModel.setDiscordToken(it) },
                        onTestDiscordConnection = { token ->
                            viewModel.setDiscordToken(token)
                            viewModel.connectDiscordRpc(token)
                        },
                        onDisconnectDiscord = { viewModel.disconnectDiscordRpc() },
                        onOpenSignInDialog = { showSignInDialog = true },
                        onSignOutDiscord = { viewModel.signOutDiscord() }
                    )
                }
            }
        }
    }

    if (showSignInDialog) {
        DiscordSignInDialog(
            initialToken = settings.discordToken,
            initialClientId = oauthClientId,
            isLoading = isFetchingProfile,
            onDismiss = { showSignInDialog = false },
            onSignInWithToken = { token ->
                viewModel.signInWithDiscordToken(token) { success ->
                    if (success) showSignInDialog = false
                }
            },
            onLaunchOAuthBrowser = { clientId ->
                val url = viewModel.buildOAuthUrlAndSaveClientId(clientId)
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }.onFailure {
                    viewModel.showToast("Unable to open browser for Discord OAuth2")
                }
            }
        )
    }
}
