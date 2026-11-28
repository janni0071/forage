package forage.app.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * UI parts only a desktop has: tooltips on hover and scrollbars to drag. The desktop app provides
 * them through [LocalExtras]; on a phone, where nothing hovers and lists scroll by swiping, they
 * are left out.
 */
interface Extras {
    @Composable
    fun Tooltip(delayMillis: Int, tooltip: @Composable () -> Unit, content: @Composable () -> Unit)

    @Composable
    fun Scrollbar(state: LazyListState, modifier: Modifier)

    @Composable
    fun Scrollbar(state: LazyGridState, modifier: Modifier)
}

private object NoExtras : Extras {
    @Composable
    override fun Tooltip(delayMillis: Int, tooltip: @Composable () -> Unit, content: @Composable () -> Unit) = content()

    @Composable
    override fun Scrollbar(state: LazyListState, modifier: Modifier) {}

    @Composable
    override fun Scrollbar(state: LazyGridState, modifier: Modifier) {}
}

val LocalExtras = staticCompositionLocalOf<Extras> { NoExtras }

/** A tooltip shown on hover, where the platform has one. */
@Composable
fun Tooltip(tooltip: @Composable () -> Unit, delayMillis: Int = 300, content: @Composable () -> Unit) =
    LocalExtras.current.Tooltip(delayMillis, tooltip, content)

@Composable
fun Scrollbar(state: LazyListState, modifier: Modifier) = LocalExtras.current.Scrollbar(state, modifier)

@Composable
fun Scrollbar(state: LazyGridState, modifier: Modifier) = LocalExtras.current.Scrollbar(state, modifier)
