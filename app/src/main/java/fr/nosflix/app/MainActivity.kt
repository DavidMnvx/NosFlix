package fr.nosflix.app

import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import fr.nosflix.app.ui.LocalClient
import fr.nosflix.app.ui.LocalDims
import fr.nosflix.app.ui.LocalIsTv
import fr.nosflix.app.ui.NosFlixRoot
import fr.nosflix.app.ui.NosFlixTheme
import fr.nosflix.app.ui.PhoneDims
import fr.nosflix.app.ui.TvDims
import android.graphics.Color as AndroidColor

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        val isTv = isTelevision()
        val fromPlayer = intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true
        if (fromPlayer) vm.openNowPlaying()
        setContent {
            NosFlixTheme {
                CompositionLocalProvider(
                    LocalIsTv provides isTv,
                    LocalDims provides if (isTv) TvDims else PhoneDims,
                    LocalClient provides vm.client,
                ) {
                    NosFlixRoot(vm, showIntro = savedInstanceState == null && !fromPlayer)
                }
            }
        }
    }

    /** Appui sur la notification du lecteur audio : on rouvre le lecteur. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) vm.openNowPlaying()
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "openPlayer"
    }

    /** Clavier (émulateur, clavier Bluetooth) : Échap = bouton Retour de la télécommande. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onRestart() {
        super.onRestart()
        // Retour du lecteur : on met à jour « Reprendre la lecture ».
        vm.refreshAfterPlayback()
    }

    private fun isTelevision(): Boolean {
        val uiMode = getSystemService(UiModeManager::class.java)
        return uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
}
