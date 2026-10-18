package com.remu.app.core.nav

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.remu.app.AppContainer
import com.remu.app.R
import com.remu.app.core.theme.Remu
import com.remu.app.core.theme.RemuTheme
import com.remu.app.data.state.Mode
import com.remu.app.feature.alerts.AlertDetailScreen
import com.remu.app.feature.alerts.OwnerHomeScreen
import com.remu.app.feature.alerts.OwnerHomeViewModel
import com.remu.app.feature.enrol.CashierHomeScreen
import com.remu.app.feature.enrol.EnrolViewModel
import com.remu.app.feature.enrol.HouseholdTarget
import com.remu.app.feature.export.ExportScreen
import com.remu.app.feature.export.ExportShare
import com.remu.app.feature.export.ExportViewModel
import com.remu.app.feature.login.LoginDestination
import com.remu.app.feature.login.LoginScreen
import com.remu.app.feature.login.LoginViewModel
import com.remu.app.feature.login.WelcomeScreen
import com.remu.app.feature.members.HouseholdScreen
import com.remu.app.feature.members.HouseholdViewModel
import com.remu.app.feature.members.MemberDetailScreen
import com.remu.app.feature.members.MemberDetailViewModel
import com.remu.app.feature.members.MemberSearchScreen
import com.remu.app.feature.members.MembersViewModel
import com.remu.app.feature.owner.OwnerSignInScreen
import com.remu.app.feature.owner.OwnerSignInViewModel
import com.remu.app.feature.owner.SignInNext
import com.remu.app.feature.pairing.PairKind
import com.remu.app.feature.pairing.PairingScreen
import com.remu.app.feature.pairing.PairingViewModel
import com.remu.app.feature.settings.CashierSettingsScreen
import com.remu.app.feature.settings.DeviceStatusScreen
import com.remu.app.feature.settings.DeviceStatusViewModel
import com.remu.app.feature.settings.InviteViewModel
import com.remu.app.feature.settings.OwnerSettingsScreen
import com.remu.app.feature.settings.StaffScreen
import com.remu.app.feature.settings.StaffViewModel
import com.remu.app.feature.settings.TokensScreen
import com.remu.app.feature.settings.TokensViewModel
import com.remu.app.feature.setup.SetupScreen
import com.remu.app.feature.setup.SetupViewModel
import com.remu.app.network.hub.HubDiscovery
import kotlinx.coroutines.flow.StateFlow

/** A visit to open after a notification tap. */
data class PendingAlert(val visitId: String, val memberId: String)

object Routes {
    const val WELCOME = "welcome"
    const val PAIR = "pair/{kind}"
    const val LOGIN = "login"
    const val OWNER_SIGN_IN = "owner_signin/{setup}"
    const val SETUP = "setup"
    const val CASHIER = "cashier?hh={hh}&label={label}"
    const val CASHIER_SETTINGS = "cashier_settings"
    const val MEMBERS = "members/{owner}"
    const val MEMBER = "member/{id}/{owner}"
    const val HOUSEHOLD = "household/{memberId}"
    const val OWNER_HOME = "owner_home"
    const val ALERT = "alert/{visitId}/{memberId}"
    const val OWNER_SETTINGS = "owner_settings"
    const val STAFF = "staff"
    const val DEVICE = "device"
    const val EXPORT = "export"
    const val INVITE = "invite"
    const val TOKENS = "tokens"

    fun pair(kind: PairKind) = "pair/${kind.name}"
    fun ownerSignIn(setup: Boolean) = "owner_signin/$setup"
    fun cashier(h: HouseholdTarget? = null) = if (h == null) "cashier" else "cashier?hh=${Uri.encode(h.id)}&label=${Uri.encode(h.label)}"
    fun members(owner: Boolean) = "members/$owner"
    fun member(id: String, owner: Boolean) = "member/${Uri.encode(id)}/$owner"
    fun household(memberId: String) = "household/${Uri.encode(memberId)}"
    fun alert(visitId: String, memberId: String) = "alert/${Uri.encode(visitId)}/${Uri.encode(memberId)}"

    /** Routes that need a staff PIN session. */
    val protected = setOf(CASHIER, CASHIER_SETTINGS, MEMBERS, MEMBER, HOUSEHOLD, OWNER_HOME, ALERT, OWNER_SETTINGS, STAFF, DEVICE, EXPORT, INVITE, TOKENS)
}

private fun NavHostController.resetTo(route: String) = navigate(route) { popUpTo(0) { inclusive = true }; launchSingleTop = true }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RemuNavHost(c: AppContainer, pendingAlert: StateFlow<PendingAlert?>? = null, nav: NavHostController = rememberNavController()) {
    val loaded by c.session.loaded.collectAsState()
    val staff by c.appState.staff.collectAsState()
    val pending = pendingAlert?.collectAsState()?.value
    val ownerMode = staff?.mode == Mode.OWNER
    RemuTheme(forceLight = !ownerMode) {
        if (!loaded) { Box(Modifier.fillMaxSize().background(Remu.colors.background)); return@RemuTheme }
        val start = if (c.session.current.paired) Routes.LOGIN else Routes.WELCOME
        val current = nav.currentBackStackEntryFlow.collectAsState(initial = null).value?.destination?.route

        // Locked by idle timeout or by "Lock": back to the PIN screen.
        LaunchedEffect(staff, current) {
            if (staff == null && current != null && current in Routes.protected) nav.resetTo(Routes.LOGIN)
        }
        LaunchedEffect(pending, staff) {
            val p = pending ?: return@LaunchedEffect
            if (staff?.mode == Mode.OWNER) nav.navigate(Routes.alert(p.visitId, p.memberId))
        }

        // Test tags double as resource ids so Maestro flows can find them (app/maestro/).
        NavHost(nav, startDestination = start, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
            composable(Routes.WELCOME) {
                WelcomeScreen(
                    language = c.language,
                    setupInProgress = c.session.current.wizard?.completed == false,
                    onSetup = { nav.navigate(if (c.session.current.ownerSignedIn) Routes.SETUP else Routes.ownerSignIn(true)) },
                    onJoinCounter = { nav.navigate(Routes.pair(PairKind.COUNTER)) },
                    onPairOwner = { nav.navigate(Routes.pair(PairKind.OWNER)) },
                )
            }
            composable(Routes.PAIR, listOf(navArgument("kind") { type = NavType.StringType })) { entry ->
                val kind = PairKind.valueOf(entry.arguments?.getString("kind") ?: PairKind.COUNTER.name)
                val context = LocalContext.current
                val vm = remuViewModel { PairingViewModel(c, kind, HubDiscovery(context.applicationContext)) }
                PairingScreen(vm, onBack = { if (!nav.popBackStack()) nav.resetTo(Routes.WELCOME) }, onPaired = { nav.resetTo(Routes.LOGIN) })
            }
            composable(Routes.LOGIN) {
                val vm = remuViewModel { LoginViewModel(c) }
                LoginScreen(vm, c.language, onDestination = { d ->
                    when (d) {
                        LoginDestination.CASHIER -> nav.resetTo(Routes.cashier())
                        LoginDestination.OWNER_HOME -> nav.resetTo(Routes.OWNER_HOME)
                        LoginDestination.OWNER_SIGN_IN -> nav.navigate(Routes.ownerSignIn(false))
                        LoginDestination.SETUP -> nav.navigate(Routes.SETUP)
                    }
                }, onPairAgain = { nav.navigate(Routes.pair(PairKind.OWNER)) })
            }
            composable(Routes.OWNER_SIGN_IN, listOf(navArgument("setup") { type = NavType.BoolType })) { entry ->
                val setup = entry.arguments?.getBoolean("setup") ?: false
                val vm = remuViewModel { OwnerSignInViewModel(c, setup) }
                OwnerSignInScreen(vm, onBack = { nav.popBackStack() }, onNext = { n ->
                    when (n) {
                        SignInNext.SETUP -> nav.navigate(Routes.SETUP) { popUpTo(Routes.OWNER_SIGN_IN) { inclusive = true } }
                        SignInNext.HOME -> if (c.appState.staff.value?.mode == Mode.OWNER) nav.resetTo(Routes.OWNER_HOME) else nav.resetTo(Routes.LOGIN)
                    }
                })
            }
            composable(Routes.SETUP) {
                val vm = remuViewModel { SetupViewModel(c) }
                SetupScreen(vm, invite = { InviteViewModel(c) }, c = c, onBack = { nav.popBackStack() }, onFinished = {
                    if (c.appState.staff.value?.mode == Mode.OWNER) nav.resetTo(Routes.OWNER_HOME) else nav.resetTo(Routes.LOGIN)
                })
            }
            composable(
                Routes.CASHIER,
                listOf(
                    navArgument("hh") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("label") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                val hh = entry.arguments?.getString("hh")
                val label = entry.arguments?.getString("label")
                val target = if (hh != null) HouseholdTarget(hh, label.orEmpty()) else null
                val front = c.session.state.collectAsState().value.useFrontCamera
                val context = LocalContext.current
                val vm = remuViewModel(key = "enrol_${front}_${hh}") { h -> EnrolViewModel(h, c, c.cameraFactory.create(context, front), target) }
                CashierHomeScreen(
                    vm,
                    onSearch = { nav.navigate(Routes.members(false)) },
                    onSettings = { nav.navigate(Routes.CASHIER_SETTINGS) },
                    onOpenMember = { nav.navigate(Routes.member(it, false)) },
                    onPairAgain = { nav.navigate(Routes.pair(PairKind.COUNTER)) },
                    onHouseholdDone = { if (target != null) nav.popBackStack() },
                )
            }
            composable(Routes.CASHIER_SETTINGS) {
                CashierSettingsScreen(c, onBack = { nav.popBackStack() }, onPairAgain = { nav.navigate(Routes.pair(PairKind.COUNTER)) }, onLock = { nav.resetTo(Routes.LOGIN) })
            }
            composable(Routes.MEMBERS, listOf(navArgument("owner") { type = NavType.BoolType })) { entry ->
                val owner = entry.arguments?.getBoolean("owner") ?: false
                val vm = remuViewModel { MembersViewModel(c, owner) }
                MemberSearchScreen(vm, onBack = { nav.popBackStack() }, onOpen = { nav.navigate(Routes.member(it, owner)) })
            }
            composable(Routes.MEMBER, listOf(navArgument("id") { type = NavType.StringType }, navArgument("owner") { type = NavType.BoolType })) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                val owner = entry.arguments?.getBoolean("owner") ?: false
                val vm = remuViewModel { MemberDetailViewModel(c, id, owner) }
                val online by c.internet.online.collectAsState()
                MemberDetailScreen(vm, online, onBack = { nav.popBackStack() }, onEditHousehold = { nav.navigate(Routes.household(it)) })
            }
            composable(Routes.HOUSEHOLD, listOf(navArgument("memberId") { type = NavType.StringType })) { entry ->
                val memberId = entry.arguments?.getString("memberId").orEmpty()
                val vm = remuViewModel { HouseholdViewModel(c, memberId) }
                HouseholdScreen(vm, onBack = { nav.popBackStack() }, onAddAdult = { id, label -> nav.navigate(Routes.cashier(HouseholdTarget(id, label))) })
            }
            composable(Routes.OWNER_HOME) {
                LaunchedEffect(Unit) { c.startOwnerRealtime() }
                val vm = remuViewModel { OwnerHomeViewModel(c) }
                OwnerHomeScreen(
                    vm,
                    onMembers = { nav.navigate(Routes.members(true)) },
                    onSettings = { nav.navigate(Routes.OWNER_SETTINGS) },
                    onOpenAlert = { v, m -> nav.navigate(Routes.alert(v, m)) },
                )
            }
            composable(Routes.ALERT, listOf(navArgument("visitId") { type = NavType.StringType }, navArgument("memberId") { type = NavType.StringType })) { entry ->
                val visitId = entry.arguments?.getString("visitId").orEmpty()
                val memberId = entry.arguments?.getString("memberId").orEmpty()
                val home = remuViewModel { OwnerHomeViewModel(c) }
                val member = remuViewModel { MemberDetailViewModel(c, memberId, true) }
                AlertDetailScreen(home, member, visitId, onBack = { if (!nav.popBackStack()) nav.resetTo(Routes.OWNER_HOME) })
            }
            composable(Routes.OWNER_SETTINGS) {
                OwnerSettingsScreen(
                    c,
                    onBack = { nav.popBackStack() },
                    onStaff = { nav.navigate(Routes.STAFF) },
                    onDevice = { nav.navigate(Routes.DEVICE) },
                    onExport = { nav.navigate(Routes.EXPORT) },
                    onInvite = { nav.navigate(Routes.INVITE) },
                    onSignedOut = { nav.resetTo(Routes.LOGIN) },
                    onPairedPhones = { nav.navigate(Routes.TOKENS) },
                )
            }
            composable(Routes.TOKENS) { TokensScreen(remuViewModel { TokensViewModel(c) }, onBack = { nav.popBackStack() }) }
            composable(Routes.STAFF) { StaffScreen(remuViewModel { StaffViewModel(c) }, onBack = { nav.popBackStack() }) }
            composable(Routes.DEVICE) {
                DeviceStatusScreen(remuViewModel { DeviceStatusViewModel(c) }, onBack = { nav.popBackStack() }, onPairHub = { nav.navigate(Routes.pair(PairKind.OWNER)) })
            }
            composable(Routes.EXPORT) {
                val title = stringResource(R.string.export_share_title)
                val share = c.exportShare ?: ExportShare { _, _ -> }
                ExportScreen(remuViewModel { ExportViewModel(c, share, title) }, onBack = { nav.popBackStack() })
            }
            composable(Routes.INVITE) {
                val vm = remuViewModel { InviteViewModel(c) }
                com.remu.app.feature.settings.InviteScreen(vm, onBack = { nav.popBackStack() })
            }
        }
    }
}
