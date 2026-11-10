package fr.pelis.iptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import fr.pelis.iptv.ui.GuideScreen
import fr.pelis.iptv.ui.HomeScreen
import fr.pelis.iptv.ui.IptvTheme
import fr.pelis.iptv.ui.MovieScreen
import fr.pelis.iptv.ui.ProfileEditScreen
import fr.pelis.iptv.ui.ProfilesScreen
import fr.pelis.iptv.ui.SeriesScreen

class MainActivity : ComponentActivity() {
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
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val s = vm.screen) {
            Screen.Profiles -> ProfilesScreen(vm)
            is Screen.EditProfile -> ProfileEditScreen(vm, s.profile)
            Screen.Home -> stateHolder.SaveableStateProvider("home") { HomeScreen(vm) }
            is Screen.Series -> SeriesScreen(vm, s.entry)
            is Screen.Movie -> MovieScreen(vm, s.entry)
            Screen.Guide -> GuideScreen(vm)
        }
    }
}
