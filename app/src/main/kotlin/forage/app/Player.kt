package forage.app

/** Where videos play (SPEC: the app finds and organizes videos, playback happens elsewhere). */
enum class Player(val label: String) {
    FREETUBE("FreeTube"),
    MPV("mpv"),
    BROWSER("Browser"),
}
