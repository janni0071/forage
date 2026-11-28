package forage.app

/** Forage Standard: everything but a site of the private vault's own (its vault is for YouTube). */
object Edition {
    const val NAME = "Standard"

    /** The release files of this version: the APK, or the Linux package. */
    fun releaseFile(version: String, mobile: Boolean) = if (mobile) "forage-$version.apk" else "forage_${version}_amd64.deb"

    val vaultSite: VaultSite? = null
}
