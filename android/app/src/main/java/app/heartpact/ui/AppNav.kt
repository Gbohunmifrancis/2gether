package app.heartpact.ui

import android.app.Activity
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
                    val me = session?.user
                    val partner = couple?.members?.firstOrNull { it.userId != me?.id }?.displayName ?: "your partner"
                    HomeScreen(myName = me?.displayName ?: "You", partnerName = partner, onSignOut = { appModel.signOut() })
                }
            }
        }
    }
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
