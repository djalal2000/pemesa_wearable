package com.bharathvishal.messagecommunicationusingwearabledatalayer

/** Default protocol labels and preference keys shared by settings and recording. */
internal object ActivityLabels {
    val professions = listOf(
        "RN - Registered Nurse",
        "AS - Certified Nursing Assistant",
        "ASH - Environmental Services worker"
    )

    fun defaults(profession: String): List<String> = when (profession) {
        "RN" -> listOf(
            "Measure temperature",
            "Inject medication",
            "Apply dressing",
            "Take blood pressure",
            "Activity 5",
            "Activity 6"
        )
        "AS" -> listOf(
            "Bed bathing",
            "Bathroom bathing",
            "Rest break",
            "Wheelchair transfer",
            "Bed preparation",
            "Climbing the stairs"
        )
        "ASH" -> listOf(
            "Floor cleaning",
            "Room cleaning",
            "Deliver food",
            "Rest break",
            "Waste collection",
            "Climbing the stairs"
        )
        else -> List(6) { "Activity ${it + 1}" }
    } + "Unlabeled activity"

    const val PREFERENCES = "activity_labels"

    fun key(profession: String, index: Int): String = "$profession:$index"
}
