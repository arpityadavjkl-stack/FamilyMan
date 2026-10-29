package com.familyguard

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.familyguard.common.AuthRepository
import com.familyguard.common.FamilyRepository
import com.familyguard.common.UserRole
import com.familyguard.common.ReferralType
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
    private val googleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            runCatching { task.getResult(com.google.android.gms.common.api.ApiException::class.java) }
                .onSuccess { account ->
                    val credential = GoogleAuthProvider.getCredential(account.idToken, null)
                    com.google.firebase.auth.FirebaseAuth.getInstance().signInWithCredential(credential)
                        .addOnFailureListener { Toast.makeText(this, it.message ?: "Google sign-in failed.", Toast.LENGTH_LONG).show() }
                }
                .onFailure { Toast.makeText(this, it.message ?: "Google sign-in failed.", Toast.LENGTH_LONG).show() }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { FamilyManRoot(onGoogleSignIn = ::startGoogleSignIn) } }
    }
    private fun startGoogleSignIn() {
        val id = resources.getIdentifier("default_web_client_id", "string", packageName)
        if (id == 0) { Toast.makeText(this, "Add a Google web client ID in Firebase setup first.", Toast.LENGTH_LONG).show(); return }
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestIdToken(getString(id)).requestEmail().build()
        googleLauncher.launch(GoogleSignIn.getClient(this, options).signInIntent)
    }
}

@Composable
private fun FamilyManRoot(onGoogleSignIn: () -> Unit) {
    val auth = remember { AuthRepository() }
    val repo = remember { FamilyRepository() }
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf(com.google.firebase.auth.FirebaseAuth.getInstance().currentUser) }
    var role by remember { mutableStateOf<UserRole?>(null) }
    var username by remember { mutableStateOf("") }
    var checked by remember { mutableStateOf(false) }
    var childLinked by remember { mutableStateOf(false) }
    var guestOpen by remember { mutableStateOf(false) }
    var referralOpen by remember { mutableStateOf(false) }
    var code by remember(user?.uid) { mutableStateOf("") }
    var friendCode by remember(user?.uid) { mutableStateOf("") }
    var children by remember(user?.uid) { mutableStateOf(emptyList<com.familyguard.common.ChildRecord>()) }
    var message by remember(user?.uid) { mutableStateOf("") }
    var busy by remember(user?.uid) { mutableStateOf(false) }
    DisposableEffect(auth) {
        val firebaseAuth = com.google.firebase.auth.FirebaseAuth.getInstance()
        val listener = com.google.firebase.auth.FirebaseAuth.AuthStateListener { user = it.currentUser }
        firebaseAuth.addAuthStateListener(listener)
        onDispose { firebaseAuth.removeAuthStateListener(listener) }
    }
    LaunchedEffect(user?.uid) {
        role = null; checked = false
        if (user != null) runCatching {
            var currentRole = repo.getRole(user!!.uid)
            if (currentRole == null && !user!!.isAnonymous) { repo.chooseRole(user!!.uid, UserRole.PARENT); currentRole = UserRole.PARENT }
            role = currentRole
            username = repo.getUsername(user!!.uid)
            childLinked = currentRole == UserRole.CHILD && repo.getChildFamilyId(user!!.uid) != null
            if (currentRole == UserRole.PARENT) {
                code = repo.createPairingCode(user!!.uid)
                children = repo.getChildren(user!!.uid)
            }
        }.onFailure { message = it.message ?: "Could not load the account." }
        checked = true
    }
    fun runAction(action: suspend () -> Unit) {
        scope.launch {
            busy = true; message = ""
            try { action() } catch (error: Exception) { message = error.message ?: "Something went wrong." }
            finally { busy = false }
        }
    }
    Surface(Modifier.fillMaxSize()) {
        when {
            user == null && guestOpen -> GuestLoginScreen(busy, message, onBack = { guestOpen = false }, onSubmit = { name, pass, create -> runAction { if (create) auth.createGuest(name, pass) else auth.signInGuest(name, pass); guestOpen = false } })
            user == null && referralOpen -> ReferralScreen(busy, message, onBack = { referralOpen = false }, onContinue = { entered -> runAction {
                val child = com.google.firebase.auth.FirebaseAuth.getInstance().signInAnonymously().await().user ?: error("Could not start the child account.")
                repo.chooseRole(child.uid, UserRole.CHILD)
                if (entered.length == FamilyRepository.CODE_LENGTH && entered.all(Char::isDigit)) {
                    val resolvedCode = repo.resolveParentReferralCode(entered)
                    repo.pairChild(child.uid, resolvedCode)
                } else repo.redeemFriendCode(child.uid, entered)
                childLinked = true; user = child; role = UserRole.CHILD; referralOpen = false; message = "Linked successfully."
            } })
            user != null && role == UserRole.CHILD && referralOpen -> ReferralScreen(busy, message, onBack = { referralOpen = false }, onContinue = { entered -> runAction {
                val child = user ?: error("Child account is unavailable.")
                if (entered.length == FamilyRepository.CODE_LENGTH && entered.all(Char::isDigit)) {
                    val resolvedCode = repo.resolveParentReferralCode(entered)
                    repo.pairChild(child.uid, resolvedCode)
                } else repo.redeemFriendCode(child.uid, entered)
                children = emptyList(); childLinked = true; referralOpen = false; message = "Referral code linked."
            } })
            user == null -> WelcomeScreen(message, onGoogleSignIn, onGuest = { guestOpen = true }, onReferral = { referralOpen = true })
            !checked -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            role == UserRole.PARENT -> ParentScreen(
                username = username, displayName = user?.displayName.orEmpty(), email = user?.email.orEmpty(), code = code, friendCode = friendCode,
                children = children, busy = busy, message = message,
                onRefresh = { runAction { code = repo.createPairingCode(user!!.uid) } },
                onFriendCode = { runAction { friendCode = repo.createFriendReferralCode(user!!.uid) } },
                onReload = { runAction { children = repo.getChildren(user!!.uid) } },
                onSignOut = { auth.signOut(); role = null; code = "" }
            )
            role == UserRole.CHILD && childLinked -> ChildHomeScreen(onAddReferral = { referralOpen = true }, onSignOut = { auth.signOut(); role = null; code = "" })
            else -> WelcomeScreen(message, onGoogleSignIn, onGuest = { guestOpen = true }, onReferral = { referralOpen = true })
        }
    }
}

@Composable
private fun WelcomeScreen(message: String, onGoogleSignIn: () -> Unit, onGuest: () -> Unit, onReferral: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("🏠", style = MaterialTheme.typography.displayLarge); Spacer(Modifier.height(12.dp))
        Text("FamilyMan", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold); Spacer(Modifier.height(32.dp))
        Button(onClick = onGoogleSignIn, modifier = Modifier.fillMaxWidth().height(58.dp)) { Text("Sign in with Google", style = MaterialTheme.typography.titleMedium) }
        OutlinedButton(onClick = onGuest, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Guest login") }
        TextButton(onClick = onReferral, modifier = Modifier.padding(top = 4.dp)) { Text("Have a referral code? Enter here") }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun GuestLoginScreen(busy: Boolean, message: String, onBack: () -> Unit, onSubmit: (String, String, Boolean) -> Unit) {
    var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = onBack) { Text("Back") }; Text("Guest login", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(username, { username = it.filter { c -> c.isLetterOrDigit() || c == '_' }.take(24) }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && username.length >= 3 && password.length >= 6, onClick = { onSubmit(username, password, false) }) { Text(if (busy) "Please wait" else "Sign in") }
        OutlinedButton(enabled = !busy && username.length >= 3 && password.length >= 6, onClick = { onSubmit(username, password, true) }) { Text("Create account") }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ReferralScreen(busy: Boolean, message: String, onBack: () -> Unit, onContinue: (String) -> Unit) {
    var referral by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text("Back") }; Text("Referral Code", style = MaterialTheme.typography.headlineMedium)
        Text("Enter your parent's 6-digit connect code or a friend's referral code.")
        OutlinedTextField(referral, { referral = it.filter(Char::isLetterOrDigit).take(8).uppercase() }, label = { Text("Referral code") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && referral.length in 6..8, onClick = { onContinue(referral) }) { Text("Continue") }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ParentScreen(username: String, displayName: String, email: String, code: String, friendCode: String, children: List<com.familyguard.common.ChildRecord>, busy: Boolean, message: String, onRefresh: () -> Unit, onFriendCode: () -> Unit, onReload: () -> Unit, onSignOut: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("FamilyMan", style = MaterialTheme.typography.headlineMedium); Text("Parent dashboard", style = MaterialTheme.typography.titleLarge)
        Text("Signed in as ${username.ifBlank { displayName.ifBlank { email.ifBlank { "Google account" } } }}")
        Text("Dashboard features will be added in later phases.")
        Text("Parent connect code", style = MaterialTheme.typography.titleMedium); Text(code, style = MaterialTheme.typography.headlineLarge)
        Text("Share privately. This code expires after 15 minutes and links one child.")
        Button(enabled = !busy, onClick = onRefresh) { Text("Make a new code") }
        Text("Friend referral code", style = MaterialTheme.typography.titleMedium)
        if (friendCode.isNotBlank()) Text(friendCode, style = MaterialTheme.typography.headlineSmall)
        OutlinedButton(enabled = !busy, onClick = onFriendCode) { Text("Create friend referral code") }
        Text("Children", style = MaterialTheme.typography.titleMedium)
        if (children.isEmpty()) Text("No linked children yet.") else children.forEach { child ->
            Text("${child.uid.take(8)}  •  ${if (child.referralType == ReferralType.FRIEND) "Friend Referral" else "Child Referral"}")
        }
        OutlinedButton(enabled = !busy, onClick = onReload) { Text("Refresh children") }
        OutlinedButton(onClick = onSignOut) { Text("Sign out") }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
    }
}

private enum class ChildSection { GAMES, CHAT, STUDY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChildHomeScreen(onAddReferral: () -> Unit, onSignOut: () -> Unit) {
    var selected by remember { mutableStateOf(ChildSection.GAMES) }
    Scaffold(topBar = { TopAppBar(title = { Text("LearnBuddy") }, actions = { TextButton(onClick = onAddReferral) { Text("Referral code") }; TextButton(onClick = onSignOut) { Text("Sign out") } }) }, bottomBar = {
        NavigationBar { listOf(ChildSection.GAMES, ChildSection.CHAT, ChildSection.STUDY).forEach { section ->
            NavigationBarItem(selected == section, { selected = section }, icon = { Text(when(section) { ChildSection.GAMES -> "🎮"; ChildSection.CHAT -> "💬"; ChildSection.STUDY -> "📚" }) }, label = { Text(section.name.lowercase().replaceFirstChar { it.uppercase() }) })
        } }
    }) { padding -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
        when (selected) {
            ChildSection.GAMES -> { Text("Games", style = MaterialTheme.typography.headlineSmall); Text("Games are coming soon.", Modifier.padding(top = 12.dp)) }
            ChildSection.CHAT -> { Text("Chat", style = MaterialTheme.typography.headlineSmall); Text("Chat is coming soon.", Modifier.padding(top = 12.dp)) }
            ChildSection.STUDY -> { Text("Study", style = MaterialTheme.typography.headlineSmall); Text("Study materials", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp)); Text("No materials yet.", Modifier.padding(top = 8.dp)) }
        }
    } }
}
