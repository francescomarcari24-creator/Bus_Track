package com.example.bustrack

import com.example.bustrack.favorites.Favorite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavoriteTest {
    @Test
    fun encodeDecode_roundTrip() {
        listOf(
            Favorite.StopFav("70240"),
            Favorite.StopFav("05000"), // zeri iniziali da conservare
            Favorite.LineFav("nMA", 0),
            Favorite.LineFav("8", 1),
            Favorite.LineFav("ROUTE|CON|PIPE", 1), // il route_id è l'ultimo campo: può contenere il separatore
        ).forEach { assertEquals(it, Favorite.decode(Favorite.encode(it))) }
    }

    @Test
    fun decode_ignoresUnknownOrBrokenRows() {
        assertNull(Favorite.decode(""))
        assertNull(Favorite.decode("trip|123"))
        assertNull(Favorite.decode("line|x|8"))
        assertNull(Favorite.decode("line|0|"))
        assertNull(Favorite.decode("stop|"))
    }
}
