package fr.nosflix.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.nosflix.app.AppViewModel
import fr.nosflix.app.data.Profile
import kotlinx.coroutines.delay

private val AvatarColors = listOf(
    Color(0xFFE50914), Color(0xFF2F80ED), Color(0xFFF2994A), Color(0xFF27AE60),
    Color(0xFF9B51E0), Color(0xFFEB5797), Color(0xFF00A3A3),
)

/** Couleur propre à chaque profil : la même sur le projecteur et sur le téléphone. */
fun profileColor(id: String): Color = AvatarColors[(id.hashCode() and 0x7fffffff) % AvatarColors.size]

/** Carré de couleur avec l'initiale du profil. */
@Composable
fun ProfileAvatar(profile: Profile, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .background(profileColor(profile.id), RoundedCornerShape(size * 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            profile.name.trim().firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            fontSize = (size.value * 0.46f).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * « Qui regarde ? » : au démarrage quand il y a plusieurs profils, ou depuis l'avatar du profil.
 * Un parent peut y ajouter des profils (sans mot de passe) et les gérer (enfant, suppression).
 */
@Composable
fun ProfilesScreen(vm: AppViewModel) {
    val isTv = LocalIsTv.current
    var managing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    val firstFocus = remember { FocusRequester() }
    BackHandler(enabled = !adding && editing == null) {
        if (managing) managing = false else vm.closeProfiles()
    }
    // Les parents d'abord, puis les autres par ordre alphabétique.
    val list = vm.profiles.ifEmpty { listOfNotNull(vm.currentProfile) }
        .sortedWith(compareBy<Profile>({ !it.isAdmin }, { it.name.lowercase() }))
    val avatar = if (isTv) 124.dp else 92.dp
    // Depuis un profil enfant, on ne gère pas les profils (il faut d'abord choisir celui d'un parent).
    val canManage = vm.canManageProfiles && !vm.isKid

    Box(
        Modifier
            .fillMaxSize()
            .background(NfColors.Black)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp, vertical = if (isTv) 48.dp else 64.dp)
                .widthIn(max = 1100.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (isTv) 30.dp else 26.dp),
        ) {
            NfLogoMark(if (isTv) 52.dp else 46.dp)
            Text(
                if (managing) "Gérer les profils" else "Qui regarde ?",
                color = Color.White,
                fontSize = if (isTv) 40.sp else 30.sp,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(if (isTv) 30.dp else 18.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                list.forEachIndexed { index, p ->
                    ProfileTile(
                        profile = p,
                        size = avatar,
                        kid = p.id in vm.kids.users,
                        current = p.id == vm.session?.userId,
                        managing = managing,
                        focusRequester = if (index == 0) firstFocus else null,
                        onClick = { if (managing) editing = p else vm.chooseProfile(p) },
                    )
                }
                if (canManage && !managing) AddTile(size = avatar, onClick = { adding = true })
            }
            if (canManage) {
                NfButton(
                    if (managing) "Terminé" else "Gérer les profils",
                    onClick = { managing = !managing },
                    icon = if (managing) NfIcons.Check else NfIcons.Settings,
                )
            }
            Text(
                if (vm.profileBusy) "Un instant…" else if (managing) "Choisis un profil pour le modifier." else " ",
                color = NfColors.Muted,
                fontSize = 14.sp,
            )
        }
    }
    LaunchedEffect(managing) {
        for (wait in listOf(120L, 300L, 700L)) {
            delay(wait)
            if (runCatching { firstFocus.requestFocus() }.isSuccess) break
        }
    }
    if (adding) AddProfileDialog(vm, onDismiss = { adding = false })
    editing?.let { p -> EditProfileDialog(vm, p, onDismiss = { editing = null }) }
}

@Composable
private fun ProfileTile(
    profile: Profile,
    size: Dp,
    kid: Boolean,
    current: Boolean,
    managing: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val lift by animateFloatAsState(if (focused) 1.08f else 1f, tween(NfMotion.EXPAND_MS, easing = NfEasing), label = "profile-lift")
    val shape = RoundedCornerShape(size * 0.16f)
    Column(
        Modifier
            .width(size + 28.dp)
            .optionalFocus(focusRequester)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .graphicsLayer { scaleX = lift; scaleY = lift }
                .border(3.dp, if (focused) Color.White else Color.Transparent, shape)
                .padding(4.dp),
        ) {
            ProfileAvatar(profile, size)
            if (managing) {
                Box(
                    Modifier
                        .size(size)
                        .background(Color.Black.copy(alpha = 0.5f), shape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(NfIcons.Settings, contentDescription = "Modifier", tint = Color.White, modifier = Modifier.size(size * 0.3f))
                }
            }
        }
        Text(
            profile.name,
            color = if (focused) Color.White else NfColors.TextSoft,
            fontSize = 16.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        val tag = when {
            kid -> "ENFANT"
            current -> "EN COURS"
            else -> null
        }
        Text(
            tag ?: " ",
            color = if (kid) NfColors.AccentText else NfColors.Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun AddTile(size: Dp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(size * 0.16f)
    Column(
        Modifier
            .width(size + 28.dp)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .border(3.dp, if (focused) Color.White else Color.Transparent, shape)
                .padding(4.dp),
        ) {
            Box(
                Modifier
                    .size(size)
                    .background(if (focused) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f), shape)
                    .border(1.dp, Color.White.copy(alpha = 0.2f), shape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(NfIcons.Plus, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.34f))
            }
        }
        Text("Ajouter", color = if (focused) Color.White else NfColors.TextSoft, fontSize = 16.sp)
        Text(" ", fontSize = 11.sp)
    }
}

/** Nouveau profil : un prénom, adulte ou enfant. Aucun mot de passe. */
@Composable
private fun AddProfileDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var kid by remember { mutableStateOf(false) }
    val create = { vm.addProfile(name, kid, onDismiss) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NfColors.Surface,
        title = { Text("Nouveau profil", color = Color.White, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                NfTextField(
                    value = name,
                    onValueChange = { name = it.take(20) },
                    label = "Prénom",
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                    onDone = create,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NfChip("Adulte", !kid, onClick = { kid = false })
                    NfChip("Enfant", kid, onClick = { kid = true })
                }
                Text(
                    if (kid) "Un profil enfant ne voit que les titres que tu autorises, sans l’Actualité ni les tendances."
                    else "Son propre historique, sa reprise et sa liste « À voir plus tard ».",
                    color = NfColors.Muted,
                    fontSize = 13.sp,
                )
            }
        },
        confirmButton = { NfButton(if (vm.profileBusy) "Création…" else "Créer", onClick = create, primary = true, enabled = !vm.profileBusy) },
        dismissButton = { NfButton("Annuler", onClick = onDismiss) },
    )
}

/** Modifier un profil : enfant ou adulte, ou le supprimer (avec son compte Jellyfin). */
@Composable
private fun EditProfileDialog(vm: AppViewModel, profile: Profile, onDismiss: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val kid = profile.id in vm.kids.users
    val current = profile.id == vm.session?.userId
    val close = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NfColors.Surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProfileAvatar(profile, 40.dp)
                Text(profile.name, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when {
                    confirmDelete -> Text(
                        "Supprimer « ${profile.name} » ? Son historique (vus, reprises, liste « À voir plus tard ») sera effacé. Ça ne se rattrape pas.",
                        color = NfColors.TextSoft,
                        fontSize = 15.sp,
                    )
                    profile.isAdmin -> Text(
                        "Compte parent : c’est lui qui gère les profils. Il ne peut pas devenir un profil enfant ni être supprimé ici.",
                        color = NfColors.TextSoft,
                        fontSize = 14.sp,
                    )
                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            NfChip("Adulte", !kid, onClick = { if (kid) vm.setProfileKid(profile, false) })
                            NfChip("Enfant", kid, onClick = { if (!kid) vm.setProfileKid(profile, true) })
                        }
                        Text(
                            if (kid) "Il ne voit que les titres autorisés : sur la fiche d’un film ou d’une série, choisis « Autoriser pour les enfants »."
                            else "Il voit toute la bibliothèque.",
                            color = NfColors.Muted,
                            fontSize = 13.sp,
                        )
                        if (current) {
                            Text("C’est le profil en cours : change de profil pour pouvoir le supprimer.", color = NfColors.Muted, fontSize = 13.sp)
                        } else {
                            NfButton("Supprimer le profil", onClick = { confirmDelete = true })
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (confirmDelete) {
                NfButton(if (vm.profileBusy) "Suppression…" else "Supprimer", onClick = { vm.deleteProfile(profile, onDismiss) }, enabled = !vm.profileBusy)
            } else {
                NfButton("Fermer", onClick = onDismiss, primary = true, focusRequester = close)
            }
        },
        dismissButton = {
            if (confirmDelete) NfButton("Annuler", onClick = { confirmDelete = false }, primary = true, focusRequester = close)
        },
    )
    LaunchedEffect(confirmDelete) {
        for (wait in listOf(100L, 250L, 500L)) {
            delay(wait)
            if (runCatching { close.requestFocus() }.isSuccess) break
        }
    }
}
