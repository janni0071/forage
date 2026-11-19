package forage.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import forage.app.ui.Extras

/** The desktop's tooltips and scrollbars, for the shared screens. */
object DesktopExtras : Extras {
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun Tooltip(delayMillis: Int, tooltip: @Composable () -> Unit, content: @Composable () -> Unit) =
        TooltipArea(tooltip = tooltip, delayMillis = delayMillis, content = content)

    @Composable
    override fun Scrollbar(state: LazyListState, modifier: Modifier) = VerticalScrollbar(rememberScrollbarAdapter(state), modifier)

    @Composable
    override fun Scrollbar(state: LazyGridState, modifier: Modifier) = VerticalScrollbar(rememberScrollbarAdapter(state), modifier)
}
