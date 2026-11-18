package forage.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import forage.core.PlatformId
import forage.core.source.Capability
import forage.core.source.ExtractorSource
import forage.core.source.VideoDetails
import forage.core.source.VideoStub
import forage.desktop.ui.ThumbnailImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue

class ThumbnailImageTest {
    @Test
    fun `a card that switches to another video loads that video's image`() {
        val asked = Collections.synchronizedList(ArrayList<String>())
        val source = object : ExtractorSource {
            override val platform = "other"
            override val capabilities = emptySet<Capability>()
            override fun video(id: PlatformId): VideoDetails = error("not used")
            override fun search(query: String): List<VideoStub> = emptyList()
            override fun watchUrl(id: PlatformId) = ""
            override fun thumbnailUrls(id: PlatformId, large: Boolean, fresh: Boolean): List<String> {
                asked += id.id
                return emptyList()
            }
        }
        val dir = Files.createTempDirectory("thumb-ui").toFile()
        val model = DesktopModel(dir, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) { source }
        val shown = mutableStateOf(PlatformId("other", "first"))
        val scene = ImageComposeScene(200, 120) { Box(Modifier.size(160.dp, 90.dp)) { ThumbnailImage(model, shown.value) } }
        fun settle() = repeat(20) { scene.render(); Thread.sleep(10) }
        try {
            settle()
            shown.value = PlatformId("other", "second")
            settle()
            assertTrue("first" in asked && "second" in asked, "asked for $asked")
        } finally {
            scene.close()
            model.close()
            dir.deleteRecursively()
        }
    }
}
