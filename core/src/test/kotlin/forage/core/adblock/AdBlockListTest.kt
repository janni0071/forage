package forage.core.adblock

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdBlockListTest {
    @Test
    fun `ad servers and ad requests are kept out, the video and the site's player are not`() {
        assertTrue(AdBlockList.blocks("https://ads.trafficjunky.net/ads?zone_id=123"))
        assertTrue(AdBlockList.blocks("https://hw-cdn2.adtng.com/delivery/abc.mp4"))
        assertTrue(AdBlockList.blocks("https://securepubads.g.doubleclick.net/gampad/ads"))
        assertTrue(AdBlockList.blocks("https://www.example.com/_xa/ads_batch?x=1"))
        assertTrue(AdBlockList.blocks("https://player.example.com/vast/preroll.xml"))
        // The site's own pages, player and video servers.
        assertFalse(AdBlockList.blocks("https://www.pornhub.com/embed/ph5f0123456789"))
        assertFalse(AdBlockList.blocks("https://ev-h.phncdn.com/hls/videos/202001/01/123/720P_4000K_123.mp4/master.m3u8"))
        assertFalse(AdBlockList.blocks("https://ei.phncdn.com/www-static/js/player.js"))
        // Not fooled by names that only look alike.
        assertFalse(AdBlockList.blocks("https://notdoubleclick.net/x"))
        assertFalse(AdBlockList.blocks("not a url"))
    }
}
