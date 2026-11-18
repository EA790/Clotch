package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.example.R
import com.example.data.RecordingMode
import com.example.editor.EditorViewModel
import com.example.recording.RecorderState
import com.example.ui.components.DiscordSignInDialog
import com.example.ui.components.TactileIconButton
import com.example.ui.components.TactileSurface
import com.example.ui.editor.EditorScreen
import com.example.ui.screens.CaptureScreen
import com.example.ui.screens.ClipsScreen
import com.example.ui.screens.DeveloperDashboardScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.PresenceScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ClotchBlue
import com.example.ui.theme.ClotchBlueMuted
import com.example.ui.theme.DiscordBlurple
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateCard
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private const val ROUTE_DEVELOPER_DASHBOARD = "developer_dashboard"

enum class ClotchRoute(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    Home("home", "Home", Icons.Filled.Home),
    Presence("presence", "Presence", Icons.AutoMirrored.Filled.FormatListBulleted),
    Recording("recording", "Record", Icons.Filled.FiberManualRecord),
    Editor("editor", "Editor", Icons.Filled.MovieCreation),
    Clips("clips", "Clips", Icons.Filled.VideoLibrary),
    Settings("settings", "Settings", Icons.Filled.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClotchApp(
    quickStartTriggerCount: Int = 0,
    oauthAccessToken: String? = null,
    onOAuthTokenConsumed: () -> Unit = {},
    viewModel: ClotchViewModel = viewModel(),
    editorViewModel: EditorViewModel = viewModel()
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ClotchRoute.Home.route

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val recorderState by viewModel.recorderState.collectAsStateWithLifecycle()
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    val isLoadingClips by viewModel.isLoadingClips.collectAsStateWithLifecycle()
    val discordState by viewModel.discordConnectionState.collectAsStateWithLifecycle()
    val discordPresence by viewModel.discordPresence.collectAsStateWithLifecycle()
    val discordProfile by viewModel.discordProfile.collectAsStateWithLifecycle()
    val detectedApps by viewModel.detectedApps.collectAsStateWithLifecycle()
    val hasUsageStatsPermission by viewModel.hasUsageStatsPermission.collectAsStateWithLifecycle()
    val oauthClientId by viewModel.oauthClientId.collectAsStateWithLifecycle()
    val isFetchingProfile by viewModel.isFetchingProfile.collectAsStateWithLifecycle()

    var showSignInDialog by remember { mutableStateOf(false) }
    var pendingCaptureAfterPermission by remember { mutableStateOf(false) }

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
        if (pendingCaptureAfterPermission) {
            pendingCaptureAfterPermission = false
            viewModel.startCountdownAndCapture { launchProjectionRequest() }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            viewModel.showToast("Microphone permission denied; recording video without mic audio")
        }
        if (pendingCaptureAfterPermission) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasNotif = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasNotif) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return@rememberLauncherForActivityResult
                }
            }
            pendingCaptureAfterPermission = false
            viewModel.startCountdownAndCapture { launchProjectionRequest() }
        }
    }

    fun requestStartCapture() {
        if (settings.needsMicPermission) {
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasMic) {
                pendingCaptureAfterPermission = true
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotif = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotif) {
                pendingCaptureAfterPermission = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        viewModel.startCountdownAndCapture { launchProjectionRequest() }
    }

    fun openUsageAccessSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(intent)
        }.onFailure {
            viewModel.showToast("Unable to open Usage Access settings")
        }
    }

    fun navigateTo(route: ClotchRoute) {
        if (currentRoute != route.route) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        if (currentRoute == ClotchRoute.Editor.route && route != ClotchRoute.Editor) {
            editorViewModel.stopPreviewForExit()
        }
        navController.navigate(route.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        coroutineScope.launch { drawerState.close() }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshUsageStatsPermission()
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

                Spacer(modifier = Modifier.height(12.dp))

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
                        TactileIconButton(
                            onClick = { coroutineScope.launch { drawerState.open() } },
                            modifier = Modifier.testTag("drawer_menu_button")
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
                            TactileSurface(
                                onClick = { showSignInDialog = true },
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .testTag("topbar_sign_in_pill"),
                                color = SlateCard,
                                shape = RoundedCornerShape(50),
                                pressedScale = 0.94f,
                                hapticType = HapticFeedbackType.LongPress
                            ) {
                                Text(
                                    text = "Sign In",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimary,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                )
                            }
                        }
                        TactileIconButton(
                            onClick = { navigateTo(ClotchRoute.Settings) },
                            modifier = Modifier.testTag("topbar_settings_button")
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
            },
            bottomBar = {
                val primaryTabs = listOf(
                    ClotchRoute.Home,
                    ClotchRoute.Recording,
                    ClotchRoute.Editor,
                    ClotchRoute.Clips,
                    ClotchRoute.Settings
                )
                ClotchBottomNavBar(
                    tabs = primaryTabs,
                    currentRoute = currentRoute,
                    onSelectTab = { dest -> navigateTo(dest) }
                )
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = ClotchRoute.Home.route,
                enterTransition = {
                    fadeIn(animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing)) +
                        slideInVertically(animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing)) {
                            it / 32
                        }
                },
                exitTransition = {
                    fadeOut(animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing))
                },
                popEnterTransition = {
                    fadeIn(animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing))
                },
                popExitTransition = {
                    fadeOut(animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing))
                },
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
                        onOpenRecording = {
                            navigateTo(ClotchRoute.Recording)
                            if (recorderState is RecorderState.Idle) {
                                requestStartCapture()
                            }
                        },
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
                        detectedApps = detectedApps,
                        hasUsageStatsPermission = hasUsageStatsPermission,
                        isFetchingProfile = isFetchingProfile,
                        onGameNameChanged = { viewModel.setGameName(it) },
                        onOpenSignInDialog = { showSignInDialog = true },
                        onSignOutDiscord = { viewModel.signOutDiscord() },
                        onConnectRpc = { viewModel.connectDiscordRpc() },
                        onDisconnectRpc = { viewModel.disconnectDiscordRpc() },
                        onRequestUsageAccess = { openUsageAccessSettings() },
                        onScanAppsNow = { viewModel.scanAppsNow() }
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

                composable(ClotchRoute.Editor.route) {
                    EditorScreen(
                        editorViewModel = editorViewModel,
                        savedClips = clips,
                        geminiApiKey = settings.geminiApiKey,
                        isEditorActive = currentRoute == ClotchRoute.Editor.route,
                        onNavigateToClips = {
                            viewModel.refreshClips()
                            navigateTo(ClotchRoute.Clips)
                        },
                        onOpenSettings = {
                            navigateTo(ClotchRoute.Settings)
                        }
                    )
                }

                composable(ClotchRoute.Clips.route) {
                    ClipsScreen(
                        clips = clips,
                        isLoadingClips = isLoadingClips,
                        onRefreshClips = { viewModel.refreshClips() },
                        onDeleteClip = { viewModel.deleteClip(it) },
                        onEditClip = { clip ->
                            editorViewModel.openRecordedClip(clip)
                            navigateTo(ClotchRoute.Editor)
                        },
                        onShowMessage = { viewModel.showToast(it) }
                    )
                }

                composable(ClotchRoute.Settings.route) {
                    SettingsScreen(
                        settings = settings,
                        discordState = discordState,
                        discordProfile = discordProfile,
                        hasUsageStatsPermission = hasUsageStatsPermission,
                        isFetchingProfile = isFetchingProfile,
                        onSelectResolution = { viewModel.setResolution(it) },
                        onSelectFps = { viewModel.setFps(it) },
                        onSelectBitrate = { viewModel.setBitrate(it) },
                        onSelectOrientation = { viewModel.setOrientation(it) },
                        onSelectVideoFormat = { viewModel.setVideoFormat(it) },
                        onSelectRecordingMode = { mode ->
                            viewModel.setRecordingMode(mode)
                            if (mode == RecordingMode.AUDIO_AND_VIDEO) {
                                val hasMic = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasMic) {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        onSelectAudioCaptureSource = { source ->
                            viewModel.setAudioCaptureSource(source)
                            if (source != com.example.data.AudioCaptureSource.NONE) {
                                val hasMic = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasMic) {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        onSelectAudioTrackMode = { trackMode ->
                            viewModel.setAudioTrackMode(trackMode)
                        },
                        onToggleRecordAudioSeparately = { enabled ->
                            viewModel.setRecordAudioSeparately(enabled)
                            if (enabled) {
                                val hasMic = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasMic) {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        onSelectCountdown = { viewModel.setCountdown(it) },
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
                        onSignOutDiscord = { viewModel.signOutDiscord() },
                        onRequestUsageAccess = { openUsageAccessSettings() },
                        onScanAppsNow = { viewModel.scanAppsNow() },
                        onGeminiApiKeyChanged = { viewModel.setGeminiApiKey(it) },
                        onOpenDeveloperDashboard = {
                            navController.navigate(ROUTE_DEVELOPER_DASHBOARD) {
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable(ROUTE_DEVELOPER_DASHBOARD) {
                    DeveloperDashboardScreen(
                        onBack = { navController.popBackStack() },
                        onShowToast = { viewModel.showToast(it) }
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
                if (url != null) {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }.onFailure {
                        viewModel.showToast("Unable to open browser for Discord OAuth2")
                    }
                }
            }
        )
    }
}

@Composable
private fun ClotchBottomNavBar(
    tabs: List<ClotchRoute>,
    currentRoute: String,
    onSelectTab: (ClotchRoute) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        color = CarbonSurface,
        contentColor = TextPrimary,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(68.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            tabs.forEach { dest ->
                val selected = currentRoute == dest.route
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = when {
                        isPressed -> 0.86f
                        selected -> 1.04f
                        else -> 1.0f
                    },
                    animationSpec = spring(
                        dampingRatio = 0.55f,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "bottom_nav_scale_${dest.route}"
                )
                val pillColor by animateColorAsState(
                    targetValue = if (selected) ClotchBlueMuted else Color.Transparent,
                    animationSpec = tween(durationMillis = 160),
                    label = "bottom_nav_pill_${dest.route}"
                )
                val iconColor by animateColorAsState(
                    targetValue = if (selected) ClotchBlue else TextSecondary,
                    animationSpec = tween(durationMillis = 160),
                    label = "bottom_nav_icon_${dest.route}"
                )
                val labelColor by animateColorAsState(
                    targetValue = if (selected) TextPrimary else TextSecondary,
                    animationSpec = tween(durationMillis = 160),
                    label = "bottom_nav_label_${dest.route}"
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = interactionSource,
                            indication = androidx.compose.material3.ripple(
                                bounded = true,
                                color = ClotchBlue
                            )
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectTab(dest)
                        }
                        .testTag("bottom_nav_${dest.route}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(pillColor)
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = dest.icon,
                            contentDescription = dest.label,
                            tint = iconColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = dest.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = labelColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
