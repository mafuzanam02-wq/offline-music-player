package com.launchpoint.stereosine.data.settings

enum class AppIconChoice(val displayName: String, val aliasClassName: String) {
    MIDNIGHT_VIOLET(
        displayName    = "Midnight Violet",
        aliasClassName = "com.launchpoint.stereosine.MainActivityAliasMidnightViolet",
    ),
    CLEAN_PURPLE(
        displayName    = "Clean Purple",
        aliasClassName = "com.launchpoint.stereosine.MainActivityAliasCleanPurple",
    ),
    DEEP_TEAL(
        displayName    = "Deep Teal",
        aliasClassName = "com.launchpoint.stereosine.MainActivityAliasDeepTeal",
    ),
    OBSIDIAN_BLACK(
        displayName    = "Obsidian Black",
        aliasClassName = "com.launchpoint.stereosine.MainActivityAliasObsidianBlack",
    ),
    OCEAN_BLUE(
        displayName    = "Ocean Blue",
        aliasClassName = "com.launchpoint.stereosine.MainActivityAliasOceanBlue",
    ),
    SUNSET_ORANGE(
        displayName    = "Sunset Orange",
        aliasClassName = "com.launchpoint.stereosine.MainActivityAliasSunsetOrange",
    ),
    ;

    companion object {
        /**
         * Fresh-install launcher icon. Must match the single activity-alias with
         * android:enabled="true" in AndroidManifest.xml.
         */
        val DEFAULT = OBSIDIAN_BLACK

        fun fromStoredName(raw: String?): AppIconChoice? =
            raw?.let { value -> entries.firstOrNull { it.name == value } }
    }
}
