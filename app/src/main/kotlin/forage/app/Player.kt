package forage.app

/** Where videos play: a player inside Forage (Android), or another app. */
enum class Player(val label: String) {
    /** Forage's own player, on the video's page (Android). */
    IN_APP("In Forage"),
    /** Whatever app the phone opens the link with: LibreTube, NewPipe, a browser. */
    OTHER_APP("Another app"),
    FREETUBE("FreeTube"),
    MPV("mpv"),
    BROWSER("Browser"),
}
