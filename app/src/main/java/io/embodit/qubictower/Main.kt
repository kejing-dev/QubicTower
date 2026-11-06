package io.embodit.qubictower

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.qubictower.ui.Containers
import io.embodit.qubictower.ui.board.BoardScreen
import io.embodit.qubictower.ui.control.ControlWindow
import io.embodit.qubictower.ui.theme.QubicTheme

/**
 * Two surfaces in Shared Space (design-package windowModel volume_plus_control_window): the tower volume (default
 * container, 0.60 x 0.42 x 0.46 m from the manifest) and the control window (Planar 560 x 600 dp) to its right.
 * First open and every reopen use Placement.right(volume) (profile D44: no "in front of you" in Shared Space).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            QubicTheme { BoardScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 560.dp, height = 600.dp),
            placement = { containers.firstOrNull { it.id == Containers.BOARD }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            QubicTheme {
                Box(Modifier.windowConstraints(minWidth = 520.dp, minHeight = 480.dp, maxWidth = 900.dp, maxHeight = 640.dp)) { ControlWindow() }
            }
        }
    }
