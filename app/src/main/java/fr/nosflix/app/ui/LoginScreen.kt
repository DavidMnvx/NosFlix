package fr.nosflix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.nosflix.app.AppViewModel
import fr.nosflix.app.BuildConfig

@Composable
fun LoginScreen(vm: AppViewModel) {
    // Proposés par défaut : la dernière adresse utilisée, sinon celle de local.properties (nosflix.homeServer).
    var server by rememberSaveable { mutableStateOf(vm.lastServer?.takeUnless { it.isBlank() } ?: BuildConfig.DEFAULT_SERVER) }
    var user by rememberSaveable { mutableStateOf(vm.lastUser?.takeUnless { it.isBlank() } ?: BuildConfig.DEFAULT_USER) }
    var password by rememberSaveable { mutableStateOf("") }
    val submit = { vm.login(server, user, password) }

    // Sur TV, rien n'est sélectionné tant qu'on n'appuie pas sur une flèche :
    // on place directement la sélection sur le premier champ encore vide.
    val isTv = LocalIsTv.current
    val serverFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!isTv) return@LaunchedEffect
        delay(150)
        val target = when {
            server.isBlank() -> serverFocus
            user.isBlank() -> userFocus
            else -> passwordFocus
        }
        runCatching { target.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(NfColors.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NfLogoMark(56.dp)
                Text("NosFlix", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("Connecte-toi à ton serveur Jellyfin", color = NfColors.TextSoft, fontSize = 17.sp)
            NfTextField(
                value = server,
                onValueChange = { server = it },
                label = "Adresse du serveur (ex. http://192.168.1.10:8096)",
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.focusRequester(serverFocus),
            )
            NfTextField(
                value = user,
                onValueChange = { user = it },
                label = "Nom d’utilisateur",
                keyboardType = KeyboardType.Text,
                modifier = Modifier.focusRequester(userFocus),
            )
            NfTextField(
                value = password,
                onValueChange = { password = it },
                label = "Mot de passe",
                keyboardType = KeyboardType.Password,
                password = true,
                imeAction = ImeAction.Done,
                onDone = submit,
                modifier = Modifier.focusRequester(passwordFocus),
            )
            vm.loginError?.let { Text(it, color = NfColors.AccentText, fontSize = 14.sp) }
            NfButton(
                text = if (vm.loggingIn) "Connexion…" else "Se connecter",
                onClick = submit,
                primary = true,
                enabled = !vm.loggingIn,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "L’adresse de ton serveur Jellyfin sur le réseau de la maison (le port est souvent 8096). Pour regarder ailleurs, ajoute ensuite une « Adresse hors de la maison » dans les Paramètres (Tailscale, par exemple).",
                color = NfColors.Muted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
fun NfTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    password: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Bouton « œil » : permet de vérifier ce qu'on a tapé (utile quand un chiffre ne passe pas).
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (password) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) NfIcons.EyeOff else NfIcons.Eye,
                        contentDescription = if (visible) "Masquer le mot de passe" else "Afficher le mot de passe",
                        tint = NfColors.Muted,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        colors = nfFieldColors(),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun nfFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = NfColors.Accent,
    unfocusedBorderColor = Color(0xFF2A2A33),
    focusedLabelColor = NfColors.AccentText,
    unfocusedLabelColor = NfColors.Muted,
    cursorColor = NfColors.Accent,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedContainerColor = NfColors.Surface,
    unfocusedContainerColor = NfColors.Surface,
    focusedPlaceholderColor = NfColors.Subtle,
    unfocusedPlaceholderColor = NfColors.Subtle,
    focusedLeadingIconColor = NfColors.Muted,
    unfocusedLeadingIconColor = NfColors.Muted,
)
