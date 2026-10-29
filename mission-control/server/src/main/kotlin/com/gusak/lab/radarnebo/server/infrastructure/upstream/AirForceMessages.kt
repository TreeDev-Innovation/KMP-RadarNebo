package com.gusak.lab.radarnebo.server.infrastructure.upstream

import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.geo.City
import com.gusak.lab.radarnebo.geo.Regions
import com.gusak.lab.radarnebo.server.domain.alert.AirAlert
import com.gusak.lab.radarnebo.server.domain.alert.AlertLocation
import com.gusak.lab.radarnebo.server.domain.alert.LocationType
import com.gusak.lab.radarnebo.server.domain.alert.ThreatSignal
import kotlin.time.Instant

/**
 * Reads the Ukrainian Air Force's warning messages ("🏍 Реактивний БпЛА через Бровари на схід Києва",
 * "Київщина: ще один реактивний БпЛА в напрямку Березані", "Реактивний БпЛА курсом на Полтаву") as alerts:
 *
 * - the weapon from its name (БпЛА / шахед → drones, крилаті → cruise, балістика → ballistic, КАБ, ракети);
 * - where it is: "через <місто>" puts it at that city (red); an oblast named as "<…>щина" puts it somewhere in the
 *   oblast (red, oblast-wide); otherwise "курсом на / в напрямку / на <місто>" names where it is heading, which is
 *   what a yellow alert means, so it becomes a yellow alert for that city.
 *
 * The whole message is kept as the threat's text, so the classifier and the heading hints read it too. Messages
 * naming no weapon or no place the map knows ("Лівобережний масив.") yield nothing.
 */
internal object AirForceMessages {
    fun parse(id: Long, text: String, at: Instant): AirAlert? {
        val type = threatType(text.lowercase()) ?: return null
        val (location, level) = through(text)?.let { cityLocation(it) to ThreatLevel.Red }
            ?: oblast(text)?.let { oblastLocation(it) to ThreatLevel.Red }
            ?: heading(text)?.let { cityLocation(it) to ThreatLevel.Yellow }
            ?: return null
        return AirAlert(
            id = id,
            location = location,
            level = level,
            startedAt = at,
            threats = listOf(ThreatSignal(type, level, at, text.trim())),
        )
    }

    private fun threatType(lower: String): ThreatType? = when {
        listOf("бпла", "шахед", "дрон", "герань", "гербер").any { it in lower } -> ThreatType.Drones
        "крилат" in lower -> ThreatType.CruiseMissiles
        "балістик" in lower || "балістичн" in lower -> ThreatType.BallisticMissiles
        Regex("""\bкаб\b""").containsMatchIn(lower) -> ThreatType.GuidedBombs
        "ракет" in lower -> ThreatType.UnspecifiedMissiles
        else -> null
    }

    /** One or two capitalised words: "Бровари", "Кривий Ріг", "Білу Церкву". */
    private const val PLACE = """([А-ЯІЇЄҐ][\p{L}'’-]+(?:\s+[А-ЯІЇЄҐ][\p{L}'’-]+)?)"""
    private val throughPhrase = Regex("""через\s+(?:м\.\s*)?$PLACE""")
    private val headingPhrase = Regex("""(?:курсом на|у напрямку|в напрямку|напрямок на|на)\s+(?:м\.\s*)?$PLACE""")

    private fun through(text: String): City? = throughPhrase.findAll(text).firstNotNullOfOrNull { city(it.groupValues[1]) }

    private fun heading(text: String): City? = headingPhrase.findAll(text).firstNotNullOfOrNull { city(it.groupValues[1]) }

    /**
     * The city a place name in any case refers to: "Полтаву", "Дніпра", "Києва". Map keywords are stems
     * ("Полтав"), but Ukrainian also alternates vowels inside the stem (Київ → Києва, Харків → Харкова), so
     * those vowels are compared loosely and the ending is dropped.
     */
    private fun city(place: String): City? {
        // "Чернігівщину" holds the stem of Чернігів, but names the oblast.
        if (oblast(place) != null) return null
        Regions.cities.firstOrNull { c -> c.keywords.any { place.contains(it, ignoreCase = true) } }?.let { return it }
        val stem = loose(place.dropLast(1))
        if (stem.length < 4) return null
        return Regions.cities.firstOrNull { c -> c.keywords.any { loose(it).startsWith(stem) } }
    }

    private fun loose(word: String) = word.lowercase().replace(Regex("[іїоеєи]"), "*")

    /** Oblasts as the messages name them, by stem: "Сумщина", "на Сумщині", "Харківщину". */
    private val oblastStems = listOf(
        "Хмельниччин" to 3, "Вінниччин" to 4, "Рівненщин" to 5, "Волин" to 8, "Дніпропетровщин" to 9, "Дніпровщин" to 9,
        "Житомирщин" to 10, "Закарпатт" to 11, "Франківщин" to 13, "Прикарпатт" to 13, "Київщин" to 14,
        "Кіровоградщин" to 15, "Кропивниччин" to 15, "Луганщин" to 16, "Миколаївщин" to 17, "Одещин" to 18,
        "Полтавщин" to 19, "Сумщин" to 20, "Тернопільщин" to 21, "Харківщин" to 22, "Херсонщин" to 23,
        "Черкащин" to 24, "Чернігівщин" to 25, "Буковин" to 26, "Чернівеччин" to 26, "Львівщин" to 27,
        "Донеччин" to 28, "Крим" to 29,
    )

    private fun oblast(text: String): Int? = oblastStems.firstOrNull { (stem, _) -> text.contains(stem, ignoreCase = true) }?.second

    private fun cityLocation(city: City) = AlertLocation(
        uid = "tg-${city.name}",
        // A keyword of the city, so it is found on the map from the title.
        title = city.keywords.first(),
        titleEn = city.name,
        type = LocationType.City,
        oblastUid = city.oblastUid,
        oblastTitle = Regions.oblast(city.oblastUid)?.nameUk.orEmpty(),
    )

    private fun oblastLocation(uid: Int) = AlertLocation(
        uid = uid.toString(),
        title = Regions.oblast(uid)?.nameUk.orEmpty(),
        titleEn = Regions.oblast(uid)?.nameEn,
        type = LocationType.Oblast,
        oblastUid = uid,
        oblastTitle = Regions.oblast(uid)?.nameUk.orEmpty(),
    )
}
