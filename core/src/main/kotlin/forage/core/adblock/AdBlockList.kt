package forage.core.adblock

import java.net.URI

/**
 * What the embedded player isn't let to load: ad and tracking servers (the request goes nowhere,
 * and a pre-roll ad with nothing to show is passed over), and requests for video ads by their
 * address on any server. The video itself comes from the site's own servers, which aren't listed.
 */
object AdBlockList {
    /** Ad networks (adult sites' own among them) and trackers; a host matches if it is one or ends with "." and one. */
    val HOSTS = setOf(
        // The ad network of the site's owner, and its ad servers.
        "trafficjunky.net", "trafficjunky.com", "adtng.com", "trafficfactory.biz", "contentabc.com",
        // Ad networks common on adult sites.
        "exoclick.com", "exosrv.com", "exdynsrv.com", "realsrv.com", "magsrv.com", "juicyads.com", "trafficstars.com",
        "tsyndicate.com", "ero-advertising.com", "plugrush.com", "adxpansion.com", "hilltopads.net", "clickadu.com",
        "onclickads.net", "popads.net", "popcash.net", "propellerads.com", "adsterra.com", "admaven.com", "adspyglass.com",
        "twinrdsrv.com", "a-ads.com", "trafficforce.com", "crakrevenue.com", "awempire.com", "adultforce.com",
        // General ad and tracking services.
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com", "googletagmanager.com",
        "googletagservices.com", "adnxs.com", "adsrvr.org", "criteo.com", "taboola.com", "outbrain.com", "scorecardresearch.com",
        "quantserve.com", "hotjar.com", "moatads.com", "amazon-adsystem.com", "pubmatic.com", "rubiconproject.com", "openx.net",
    )

    /** Parts of an address that ask for an ad, whichever server it's on (lower case). */
    val PATHS = listOf("/_xa/ads", "/vast", "vast.xml", "vpaid", "/adserver", "/ads/serve")

    /** Whether the player is kept from loading [url]. */
    fun blocks(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        if (blocksHost(host)) return true
        val path = (uri.rawPath.orEmpty() + "?" + uri.rawQuery.orEmpty()).lowercase()
        return PATHS.any { it in path }
    }

    fun blocksHost(host: String): Boolean {
        var h = host
        while (true) {
            if (h in HOSTS) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }
}
