package fr.pelis.iptv

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import fr.pelis.iptv.ui.DeviceSetupScreen
import fr.pelis.iptv.ui.GuideScreen
import fr.pelis.iptv.ui.LocalTvMode
import fr.pelis.iptv.ui.FocusMemory
import fr.pelis.iptv.ui.LocalFocusMemory
import androidx.compose.runtime.remember
import fr.pelis.iptv.ui.HomeScreen
import fr.pelis.iptv.ui.IptvTheme
import fr.pelis.iptv.ui.MovieScreen
import fr.pelis.iptv.ui.ProfileEditScreen
import fr.pelis.iptv.ui.ProfilesScreen
import fr.pelis.iptv.ui.SeriesScreen
import fr.pelis.iptv.ui.UpdateDialog

// FragmentActivity : requis par le bouton Cast (boîte de dialogue de choix d'appareil).
class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { IptvTheme { App(vm) } }
    }
}

@Composable
private fun App(vm: MainViewModel) {
    // Conserve la position de défilement de l'accueil quand on ouvre une fiche puis revient.
    val stateHolder = rememberSaveableStateHolder()
    CompositionLocalProvider(LocalTvMode provides vm.tvMode) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val s = vm.screen) {
            Screen.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is Screen.DeviceSetup -> DeviceSetupScreen(vm, s.firstLaunch)
            Screen.Profiles -> ProfilesScreen(vm)
            is Screen.EditProfile -> ProfileEditScreen(vm, s.profile)
            Screen.Home -> stateHolder.SaveableStateProvider("home:${vm.activeProfile?.id}") {
                // Focus rendu à l'élément quitté au retour d'une fiche (mode TV).
                val memory = remember { FocusMemory(vm.lastFocusedItemKey) { vm.lastFocusedItemKey = it } }
                CompositionLocalProvider(LocalFocusMemory provides memory) { HomeScreen(vm) }
            }
            is Screen.Series -> SeriesScreen(vm, s.entry)
            is Screen.Movie -> MovieScreen(vm, s.entry)
            Screen.Guide -> GuideScreen(vm)
        }
        UpdateDialog(vm)
    }
    }
}
