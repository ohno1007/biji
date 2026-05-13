package com.biji.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.biji.notes.ui.glass.AnimatedAuroraBackground
import com.biji.notes.ui.glass.LiquidGlassScaffold
import com.biji.notes.ui.glass.LocalLiquidGlass
import com.biji.notes.ui.notes.NoteEditScreen
import com.biji.notes.ui.notes.NotesListScreen
import com.biji.notes.ui.notes.NotesViewModel
import com.biji.notes.ui.theme.BijiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val repo = (application as BijiApp).repository
        setContent {
            BijiTheme {
                val vm: NotesViewModel = viewModel(factory = NotesViewModel.factory(repo))
                AppRoot(vm)
            }
        }
    }
}

private sealed interface Destination {
    data object List : Destination
    data class Edit(val noteId: Long) : Destination
}

@Composable
private fun AppRoot(vm: NotesViewModel) {
    var destination by remember { mutableStateOf<Destination>(Destination.List) }

    LiquidGlassScaffold(
        background = { AnimatedAuroraBackground() },
        content = {
            val glass = LocalLiquidGlass.current
            Box(Modifier.fillMaxSize()) {
                when (val d = destination) {
                    is Destination.List -> {
                        NotesListScreen(
                            vm = vm,
                            glass = glass,
                            onNoteClick = { destination = Destination.Edit(it) },
                            onNewNote = { destination = Destination.Edit(0L) }
                        )
                    }
                    is Destination.Edit -> {
                        BackHandler { destination = Destination.List }
                        NoteEditScreen(
                            vm = vm,
                            noteId = d.noteId,
                            glass = glass,
                            onBack = { destination = Destination.List }
                        )
                    }
                }
            }
        }
    )
}
