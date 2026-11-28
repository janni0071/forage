package forage.cli

/** Minimal `--name value` parser: enough for the M0 spike without a dependency. */
class Args(raw: List<String>, private val valueOptions: Set<String>, private val flagOptions: Set<String> = emptySet()) {
    val positional = ArrayList<String>()
    private val options = HashMap<String, MutableList<String>>()
    private val flags = HashSet<String>()

    init {
        var i = 0
        while (i < raw.size) {
            val arg = raw[i]
            when {
                !arg.startsWith("--") -> positional += arg
                arg in valueOptions -> {
                    val value = raw.getOrNull(++i) ?: throw UsageError("$arg needs a value")
                    options.getOrPut(arg) { ArrayList() } += value
                }
                arg in flagOptions -> flags += arg
                else -> throw UsageError("Unknown option: $arg")
            }
            i++
        }
    }

    fun flag(name: String): Boolean = name in flags
    fun all(name: String): List<String> = options[name].orEmpty()
    fun one(name: String): String? = all(name).lastOrNull()
    fun int(name: String): Int? = one(name)?.let { it.toIntOrNull() ?: throw UsageError("$name needs a number, got '$it'") }
}

class UsageError(message: String) : Exception(message)
