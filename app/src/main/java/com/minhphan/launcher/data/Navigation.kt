package com.minhphan.launcher.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/** The two places the home screen has a one-tap route to. */
enum class Place(val wazeFavorite: String) {
    Home("home"),
    Work("work"),
}

enum class Navigator(val packageName: String) {
    Waze("com.waze"),
    GoogleMaps("com.google.android.apps.maps"),
}

/** What the user picked in Settings: a fixed app, or [Auto] = Waze when it is installed, otherwise Google Maps. */
enum class NavigatorChoice { Auto, Waze, GoogleMaps }

enum class NavResult { Started, NeedsAddress, NotInstalled }

/**
 * The navigator to use, or null when none is installed. An explicit choice is honoured even if that app is
 * missing, so the user is told to install it instead of being silently sent somewhere else.
 */
fun resolveNavigator(choice: NavigatorChoice, installedPackages: Collection<String>): Navigator? = when (choice) {
    NavigatorChoice.Waze -> Navigator.Waze
    NavigatorChoice.GoogleMaps -> Navigator.GoogleMaps
    NavigatorChoice.Auto -> Navigator.entries.firstOrNull { it.packageName in installedPackages }
}

/**
 * Deep link that starts driving navigation to [place]. Waze without an [address] uses the Home / Work
 * favourite saved inside Waze; Google Maps has no such shortcut, so it needs an address (null = not possible).
 * Formats are from developers.google.com/waze/deeplinks and developers.google.com/maps/documentation/urls/android-intents.
 */
fun navigationUri(navigator: Navigator, place: Place, address: String): String? {
    val query = address.trim()
    return when (navigator) {
        Navigator.Waze ->
            if (query.isEmpty()) "https://waze.com/ul?favorite=${place.wazeFavorite}&navigate=yes"
            else "https://waze.com/ul?q=${encode(query)}&navigate=yes"
        Navigator.GoogleMaps ->
            if (query.isEmpty()) null else "google.navigation:q=${encode(query)}&mode=d"
    }
}

// Waze wants spaces as %20; URLEncoder writes '+', which is only right inside form data.
private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

/** Starts navigation in [navigator]'s own app. Pass an Activity context so the map opens over the launcher. */
fun startNavigation(context: Context, navigator: Navigator?, place: Place, address: String): NavResult {
    if (navigator == null) return NavResult.NotInstalled
    val uri = navigationUri(navigator, place, address) ?: return NavResult.NeedsAddress
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        .setPackage(navigator.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        NavResult.Started
    } catch (_: ActivityNotFoundException) {
        NavResult.NotInstalled
    }
}
