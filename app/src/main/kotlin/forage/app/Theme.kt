package forage.app

enum class Theme(val label: String) {
    SYSTEM("Follow the system"), LIGHT("Light"), DARK("Dark"),
}

/** Looks the private vault can wear instead of the normal pastel theme; each is always dark. */
enum class VaultLook(val label: String) { VELVET("Velvet"), NEON("Neon"), NOIR("Noir") }
