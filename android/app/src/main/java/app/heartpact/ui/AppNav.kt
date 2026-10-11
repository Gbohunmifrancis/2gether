package app.heartpact.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.heartpact.alarm.AlarmActivity
import app.heartpact.alarm.AlarmNotifier
import app.heartpact.alarm.AlarmSlot
import app.heartpact.data.utc
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import app.heartpact.data.CoupleSide
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.NavType
import androidx.navigation.navArgument
import app.heartpact.ui.food.FoodEntryScreen
import app.heartpact.ui.food.FoodEntryViewModel
import app.heartpact.ui.food.FoodScanScreen
import app.heartpact.ui.food.FoodScanViewModel
import app.heartpact.ui.food.FoodTab
import app.heartpact.ui.food.FoodViewModel
import java.io.File
import app.heartpact.ui.album.AlbumTab
import app.heartpact.ui.album.AlbumViewModel
import app.heartpact.ui.home.Tab
import app.heartpact.ui.theme.Side
import app.heartpact.ui.pacts.PactsTab
import app.heartpact.ui.pacts.PactsViewModel
import app.heartpact.ui.today.MyDare
import app.heartpact.ui.today.NewDareScreen
import app.heartpact.ui.today.NewDareViewModel
import app.heartpact.ui.today.TodayTab
import app.heartpact.ui.today.TodayViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.heartpact.data.AppContainer
import app.heartpact.ui.auth.SignInScreen
import app.heartpact.ui.auth.SignInViewModel
import app.heartpact.ui.auth.SignUpScreen
import app.heartpact.ui.auth.SignUpViewModel
import app.heartpact.ui.auth.WelcomeScreen
import app.heartpact.ui.home.HomeScreen
import app.heartpact.ui.onboarding.OnboardingScreen
import app.heartpact.ui.pair.PairScreen
import app.heartpact.ui.pair.PairViewModel
import app.heartpact.ui.splash.AnimatedSplash
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.HeartpactTheme

private object Routes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val WELCOME = "welcome"
    const val SIGN_UP = "signup"
    const val SIGN_IN = "signin"
    const val PAIR = "pair"
    const val HOME = "home"
    const val NEW_DARE = "newdare"
    const val FOOD_SCAN = "foodscan?uri={uri}"
    const val FOOD_ENTRY = "foodentry?id={id}"
    fun foodScan(uri: Uri) = "foodscan?uri=" + Uri.encode(uri.toString())
    fun foodEntry(id: String?) = if (id == null) "foodentry" else "foodentry?id=$id"
}

private fun Destination.route() = when (this) {
    Destination.Onboarding -> Routes.ONBOARDING
    Destination.Welcome -> Routes.WELCOME
    Destination.Pair -> Routes.PAIR
    Destination.Home -> Routes.HOME
}

@Composable
fun HeartpactApp(container: AppContainer, appModel: AppViewModel) {
    val side by appModel.side.collectAsStateWithLifecycle()
    HeartpactTheme(actor = side) {
        val nav = rememberNavController()
        val destination by appModel.destination.collectAsStateWithLifecycle()
        var splashDone by remember { mutableStateOf(false) }
        val entry by nav.currentBackStackEntryAsState()
        StatusBarIcons(dark = entry?.destination?.route != Routes.SPLASH && !Heartpact.colors.isNight)

        // Leave the splash once it has played and we know where to go; later decisions (sign out,
        // linking) replace the whole stack so Back never returns to a screen that no longer applies.
        LaunchedEffect(splashDone, destination) {
            val target = destination ?: return@LaunchedEffect
            if (!splashDone) return@LaunchedEffect
            val route = target.route()
            if (nav.currentDestination?.route != route) nav.resetTo(route)
        }

        Box(Modifier.fillMaxSize().background(Heartpact.colors.page)) {
            NavHost(nav, startDestination = Routes.SPLASH) {
                composable(Routes.SPLASH) { AnimatedSplash(onFinished = { splashDone = true }) }
                composable(Routes.ONBOARDING) { OnboardingScreen(onDone = { appModel.finishOnboarding() }) }
                composable(Routes.WELCOME) { WelcomeScreen(onCreateAccount = { nav.navigate(Routes.SIGN_UP) }, onSignIn = { nav.navigate(Routes.SIGN_IN) }) }
                composable(Routes.SIGN_UP) {
                    val model: SignUpViewModel = viewModel(factory = viewModelFactory { initializer { SignUpViewModel(container.auth) } })
                    val state by model.state.collectAsStateWithLifecycle()
                    SignUpScreen(state, model::edit, { model.submit { appModel.refresh() } }, onBack = { nav.popBackStack() })
                }
                composable(Routes.SIGN_IN) {
                    val model: SignInViewModel = viewModel(factory = viewModelFactory { initializer { SignInViewModel(container.auth) } })
                    val state by model.state.collectAsStateWithLifecycle()
                    SignInScreen(state, model::email, model::password, { model.submit { appModel.refresh() } }, model::forgotPassword,
                        onCreateAccount = { nav.navigate(Routes.SIGN_UP) { popUpTo(Routes.WELCOME) } }, onBack = { nav.popBackStack() })
                }
                composable(Routes.PAIR) {
                    val session by appModel.session.collectAsStateWithLifecycle()
                    val userId = session?.user?.id ?: return@composable
                    val token = appModel.pendingInviteToken.also { appModel.pendingInviteToken = null }
                    val model: PairViewModel = viewModel(factory = viewModelFactory { initializer { PairViewModel(container.couple, userId, token) } })
                    val state by model.state.collectAsStateWithLifecycle()
                    LaunchedEffect(state.linked) { if (state.linked) appModel.refresh() }
                    PairScreen(state, model::code, model::startEntering, model::stopEntering, model::submitCode, model::newInvite, onSignOut = { appModel.signOut() })
                }
                composable(Routes.HOME) {
                    val session by appModel.session.collectAsStateWithLifecycle()
                    val couple by appModel.couple.collectAsStateWithLifecycle()
                    val me = session?.user ?: return@composable
                    val partner = couple?.members?.firstOrNull { it.userId != me.id }?.displayName ?: "your partner"
                    val application = LocalContext.current.applicationContext as Application
                    val context = LocalContext.current
                    val todayModel: TodayViewModel = viewModel(factory = viewModelFactory { initializer { TodayViewModel(application, me.id, partner) } })
                    val todayUi by todayModel.ui.collectAsStateWithLifecycle()
                    // Back from Settings (permissions) or from a dare: re-read readiness and today.
                    LifecycleResumeEffect(Unit) {
                        todayModel.refresh()
                        onPauseOrDispose { }
                    }
                    val pactsModel: PactsViewModel = viewModel(factory = viewModelFactory { initializer { PactsViewModel(application, me.id) } })
                    val pactsUi by pactsModel.ui.collectAsStateWithLifecycle()
                    val albumModel: AlbumViewModel = viewModel(factory = viewModelFactory { initializer { AlbumViewModel(application) } })
                    val albumUi by albumModel.ui.collectAsStateWithLifecycle()
                    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { albumModel.add(it) }
                    val sides = couple?.members.orEmpty().associate { it.userId to if (it.side == CoupleSide.Right) Side.Right else Side.Left }
                    val requestedTab by appModel.openTab.collectAsStateWithLifecycle()
                    val foodModel: FoodViewModel = viewModel(factory = viewModelFactory { initializer { FoodViewModel(container.food) } })
                    val foodUi by foodModel.ui.collectAsStateWithLifecycle()
                    // Back from logging a meal (or from anywhere): the day's numbers may have moved.
                    LifecycleResumeEffect(Unit) {
                        foodModel.refresh()
                        onPauseOrDispose { }
                    }
                    var mealPhoto by remember { mutableStateOf<Uri?>(null) }
                    val takeMeal = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved -> mealPhoto?.takeIf { saved }?.let { nav.navigate(Routes.foodScan(it)) } }
                    val pickMeal = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { nav.navigate(Routes.foodScan(it)) } }
                    val pickMealPhoto = { pickMeal.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    val snapMeal = {
                        val file = File(File(context.cacheDir, "food").apply { mkdirs() }, "meal-${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        mealPhoto = uri
                        takeMeal.launch(uri)
                    }
                    // The app declares CAMERA (for rep counting), so the system camera needs it granted too.
                    val cameraForMeal = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) snapMeal() else pickMealPhoto() }
                    HomeScreen(myName = me.displayName, partnerName = partner, onSignOut = { appModel.signOut() },
                        requestedTab = requestedTab?.let { name -> Tab.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } },
                        onRequestedTab = appModel::tabOpened,
                        onTab = {
                            when (it) {
                                Tab.Pacts -> pactsModel.refresh()
                                Tab.Album -> albumModel.refresh()
                                Tab.Food -> foodModel.refresh()
                                else -> Unit
                            }
                        },
                        food = {
                            FoodTab(
                                ui = foodUi, partnerName = partner,
                                onSnap = {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) snapMeal()
                                    else cameraForMeal.launch(Manifest.permission.CAMERA)
                                },
                                onChoosePhoto = pickMealPhoto,
                                onLogByHand = { nav.navigate(Routes.foodEntry(null)) },
                                onOpenEntry = { nav.navigate(Routes.foodEntry(it.id)) },
                                onSaveTargets = foodModel::saveTargets,
                            )
                        },
                        album = {
                            AlbumTab(
                                ui = albumUi, sides = sides, localModel = albumModel::localModel,
                                onAdd = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                onSync = albumModel::sync, onRemove = { albumModel.remove(it) }, onRetry = { albumModel.retry(it) },
                                onLoadMore = albumModel::loadMore, onDismissResult = albumModel::dismissResult, unmetered = albumModel::onUnmetered,
                            )
                        },
                        pacts = { PactsTab(pactsUi, partner, onStop = pactsModel::stop, onNewDare = { nav.navigate(Routes.NEW_DARE) }) },
                        today = {
                        TodayTab(
                            ui = todayUi,
                            partnerName = partner,
                            onStartCamera = { dare -> context.startActivity(alarmIntent(context, dare, partner)) },
                            onDoItNow = { dare -> context.startActivity(alarmIntent(context, dare, partner, early = true)) },
                            onCallOff = todayModel::callOffTogether,
                            onKeep = todayModel::keepIt,
                            onNewDare = { nav.navigate(Routes.NEW_DARE) },
                        )
                    })
                }
                composable(Routes.FOOD_SCAN, arguments = listOf(navArgument("uri") { type = NavType.StringType })) { entry ->
                    val uri = Uri.parse(entry.arguments?.getString("uri") ?: return@composable)
                    val application = LocalContext.current.applicationContext as Application
                    val model: FoodScanViewModel = viewModel(factory = viewModelFactory { initializer { FoodScanViewModel(application, uri) } })
                    val state by model.ui.collectAsStateWithLifecycle()
                    FoodScanScreen(state, scansLeft = null, onPortion = model::portion, onMeal = model::meal,
                        onLog = { model.log { nav.popBackStack() } }, onRetry = { model.scan() },
                        onLogByHand = { nav.navigate(Routes.foodEntry(null)) { popUpTo(Routes.HOME) } }, onBack = { nav.popBackStack() })
                }
                composable(Routes.FOOD_ENTRY, arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
                    val id = entry.arguments?.getString("id")
                    val model: FoodEntryViewModel = viewModel(factory = viewModelFactory { initializer { FoodEntryViewModel(container.food, id) } })
                    val state by model.ui.collectAsStateWithLifecycle()
                    FoodEntryScreen(state, model::description, model::meal, model::calories,
                        onSave = { model.save { nav.popBackStack() } }, onDelete = { model.delete { nav.popBackStack() } }, onBack = { nav.popBackStack() })
                }
                composable(Routes.NEW_DARE) {
                    val couple by appModel.couple.collectAsStateWithLifecycle()
                    val session by appModel.session.collectAsStateWithLifecycle()
                    val partner = couple?.members?.firstOrNull { it.userId != session?.user?.id } ?: return@composable
                    val model: NewDareViewModel = viewModel(factory = viewModelFactory { initializer { NewDareViewModel(container.exercise, partner.userId) } })
                    val state by model.state.collectAsStateWithLifecycle()
                    NewDareScreen(state, partner.displayName, model::type, model::step, model::time, model::window, { model.send { nav.popBackStack() } }, onBack = { nav.popBackStack() })
                }
            }
        }
    }
}

/** Today's "Start on camera" opens the same full-screen flow the alarm uses. */
private fun alarmIntent(context: Context, dare: MyDare, partnerName: String, early: Boolean = false): Intent {
    val occurrence = dare.occurrence
    val slot = AlarmSlot(
        goalId = dare.goal.id, title = dare.goal.title, exerciseType = dare.goal.exerciseType, target = dare.goal.target,
        dueAtEpochMs = dare.dueAt.toEpochMilli(),
        deadlineEpochMs = (occurrence?.let { utc(it.completeByUtc ?: it.deadlineUtc) } ?: dare.dueAt.plusSeconds(dare.goal.graceMinutes * 60L)).toEpochMilli(),
        setByName = if (dare.setByPartner) partnerName else null,
        occurrenceId = occurrence?.id,
        early = early,
    )
    return Intent(context, AlarmActivity::class.java).putExtra(AlarmNotifier.EXTRA_SLOT, AlarmNotifier.encode(slot))
}

private fun NavHostController.resetTo(route: String) = navigate(route) {
    popUpTo(graph.id) { inclusive = true }
    launchSingleTop = true
}

/** Light status-bar icons over the indigo splash and at night; dark icons on the day page. */
@Composable
private fun StatusBarIcons(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = dark
            isAppearanceLightNavigationBars = dark
        }
    }
}
