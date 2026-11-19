package com.tobenamed.nav.live

/** Presentation of the provider's next action; completed step-start turns are not repeated. */
enum class ManeuverCue(val title: String) {
    STRAIGHT("Continue straight"), LEFT("Turn left"), RIGHT("Turn right"),
    BEAR_LEFT("Bear left"), BEAR_RIGHT("Bear right"), U_TURN("Make a U-turn"),
    ROUNDABOUT_LEFT("At the roundabout"), ROUNDABOUT_RIGHT("At the roundabout"), FOLLOW_ROUTE("Follow directions");

    companion object {
        fun fromProvider(value: String): ManeuverCue = when (value) {
            "UTURN_LEFT", "UTURN_RIGHT" -> U_TURN
            "ROUNDABOUT_LEFT" -> ROUNDABOUT_LEFT
            "ROUNDABOUT_RIGHT" -> ROUNDABOUT_RIGHT
            "TURN_SLIGHT_LEFT", "FORK_LEFT", "RAMP_LEFT" -> BEAR_LEFT
            "TURN_SLIGHT_RIGHT", "FORK_RIGHT", "RAMP_RIGHT" -> BEAR_RIGHT
            "TURN_LEFT", "TURN_SHARP_LEFT" -> LEFT
            "TURN_RIGHT", "TURN_SHARP_RIGHT" -> RIGHT
            "STRAIGHT", "DEPART", "NAME_CHANGE", "MANEUVER_UNSPECIFIED", "" -> STRAIGHT
            else -> FOLLOW_ROUTE
        }

        /** The backend requests English instructions. Keep the full original text in Details. */
        fun streetLabel(instruction: String): String? = Regex("\\b(?:onto|on)\\s+(.+?)(?:\\s+toward\\b|$)", RegexOption.IGNORE_CASE)
            .find(instruction)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
