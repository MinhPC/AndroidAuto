package com.minhphan.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationTest {
    @Test
    fun wazeWithoutAddressUsesItsOwnSavedFavourite() {
        assertEquals("https://waze.com/ul?favorite=home&navigate=yes", navigationUri(Navigator.Waze, Place.Home, ""))
        assertEquals("https://waze.com/ul?favorite=work&navigate=yes", navigationUri(Navigator.Waze, Place.Work, "   "))
    }

    @Test
    fun wazeWithAddressSearchesAndNavigates() {
        assertEquals(
            "https://waze.com/ul?q=66%20Acacia%20Avenue&navigate=yes",
            navigationUri(Navigator.Waze, Place.Home, "66 Acacia Avenue"),
        )
    }

    @Test
    fun vietnameseTextAndSymbolsAreEncoded() {
        assertEquals(
            "https://waze.com/ul?q=%C4%90%C3%A0%20N%E1%BA%B5ng%20%26%20Hu%E1%BA%BF&navigate=yes",
            navigationUri(Navigator.Waze, Place.Work, "Đà Nẵng & Huế"),
        )
    }

    @Test
    fun googleMapsNeedsAnAddress() {
        assertNull(navigationUri(Navigator.GoogleMaps, Place.Home, ""))
        assertEquals(
            "google.navigation:q=16.0544%2C108.2022&mode=d",
            navigationUri(Navigator.GoogleMaps, Place.Home, "16.0544,108.2022"),
        )
    }

    @Test
    fun autoPrefersWazeThenGoogleMaps() {
        assertEquals(Navigator.Waze, resolveNavigator(NavigatorChoice.Auto, listOf("com.google.android.apps.maps", "com.waze")))
        assertEquals(Navigator.GoogleMaps, resolveNavigator(NavigatorChoice.Auto, listOf("com.google.android.apps.maps")))
        assertNull(resolveNavigator(NavigatorChoice.Auto, emptyList()))
    }

    @Test
    fun anExplicitChoiceIsKeptEvenWhenNotInstalled() {
        assertEquals(Navigator.Waze, resolveNavigator(NavigatorChoice.Waze, listOf("com.google.android.apps.maps")))
    }
}
