package com.gusak.lab.radarnebo.server.infrastructure.simulation

import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.geo.City
import com.gusak.lab.radarnebo.geo.Regions
import com.gusak.lab.radarnebo.server.domain.alert.AirAlert
import com.gusak.lab.radarnebo.server.domain.alert.AlertLocation
import com.gusak.lab.radarnebo.server.domain.alert.LocationDirectory
import com.gusak.lab.radarnebo.server.domain.alert.LocationType
import kotlin.time.Instant

/** A settlement the simulated warning system declares alerts for. */
internal class Settlement(val city: City, val location: AlertLocation)

/**
 * The places the simulation alerts: the regions, and the government-controlled cities and towns of
 * [Regions.cities] as city-level locations. Kyiv is a region of its own (UID 31), like in alerts.in.ua.
 */
internal object SimulatedLocations {
    // Declared first: the settlements below are built from it when the object initialises.
    private val UKRAINIAN_NAMES = mapOf(
        "Kharkiv" to "Харків", "Odesa" to "Одеса", "Dnipro" to "Дніпро", "Lviv" to "Львів", "Zaporizhzhia" to "Запоріжжя",
        "Kryvyi Rih" to "Кривий Ріг", "Mykolaiv" to "Миколаїв", "Vinnytsia" to "Вінниця", "Kherson" to "Херсон",
        "Poltava" to "Полтава", "Chernihiv" to "Чернігів", "Cherkasy" to "Черкаси", "Sumy" to "Суми", "Zhytomyr" to "Житомир",
        "Khmelnytskyi" to "Хмельницький", "Rivne" to "Рівне", "Kropyvnytskyi" to "Кропивницький",
        "Ivano-Frankivsk" to "Івано-Франківськ", "Ternopil" to "Тернопіль", "Lutsk" to "Луцьк", "Uzhhorod" to "Ужгород",
        "Chernivtsi" to "Чернівці", "Kramatorsk" to "Краматорськ", "Sloviansk" to "Слов'янськ", "Kremenchuk" to "Кременчук",
        "Bila Tserkva" to "Біла Церква", "Brovary" to "Бровари", "Pavlohrad" to "Павлоград", "Nikopol" to "Нікополь",
        "Izmail" to "Ізмаїл", "Shostka" to "Шостка", "Konotop" to "Конотоп", "Kupiansk" to "Куп'янськ", "Nizhyn" to "Ніжин",
        "Berestyn" to "Берестин", "Voznesensk" to "Вознесенськ", "Pervomaisk" to "Первомайськ", "Uman" to "Умань",
        "Haisyn" to "Гайсин", "Pryluky" to "Прилуки", "Bakhmach" to "Бахмач", "Novhorod-Siverskyi" to "Новгород-Сіверський",
        "Koriukivka" to "Корюківка", "Okhtyrka" to "Охтирка", "Romny" to "Ромни", "Lebedyn" to "Лебедин", "Hlukhiv" to "Глухів",
        "Trostianets" to "Тростянець", "Chuhuiv" to "Чугуїв", "Izium" to "Ізюм", "Lozova" to "Лозова",
        "Bohodukhiv" to "Богодухів", "Balakliia" to "Балаклія", "Zolochiv" to "Золочів", "Lubny" to "Лубни",
        "Myrhorod" to "Миргород", "Hadiach" to "Гадяч", "Horishni Plavni" to "Горішні Плавні", "Boryspil" to "Бориспіль",
        "Fastiv" to "Фастів", "Vasylkiv" to "Васильків", "Obukhiv" to "Обухів", "Irpin" to "Ірпінь", "Bucha" to "Буча",
        "Pereiaslav" to "Переяслав", "Yahotyn" to "Яготин", "Smila" to "Сміла", "Zolotonosha" to "Золотоноша", "Kaniv" to "Канів",
        "Oleksandriia" to "Олександрія", "Znamianka" to "Знам'янка", "Kamianske" to "Кам'янське", "Samar" to "Самар",
        "Marhanets" to "Марганець", "Zhovti Vody" to "Жовті Води", "Bashtanka" to "Баштанка",
        "Pivdennoukrainsk" to "Південноукраїнськ", "Chornomorsk" to "Чорноморськ",
        "Bilhorod-Dnistrovskyi" to "Білгород-Дністровський", "Podilsk" to "Подільськ", "Pivdenne" to "Південне",
        "Zhmerynka" to "Жмеринка", "Koziatyn" to "Козятин", "Khmilnyk" to "Хмільник", "Ladyzhyn" to "Ладижин",
        "Berdychiv" to "Бердичів", "Korosten" to "Коростень", "Zviahel" to "Звягель",
        "Kamianets-Podilskyi" to "Кам'янець-Подільський", "Shepetivka" to "Шепетівка", "Starokostiantyniv" to "Старокостянтинів",
        "Drohobych" to "Дрогобич", "Stryi" to "Стрий", "Sheptytskyi" to "Шептицький", "Dubno" to "Дубно", "Varash" to "Вараш",
        "Kovel" to "Ковель", "Volodymyr" to "Володимир",
    )

    const val ZAPORIZHZHIA_UID = 12
    const val KYIV_OBLAST_UID = 14
    const val LUHANSK_UID = 16
    const val MYKOLAIV_UID = 17
    const val ODESA_UID = 18
    const val KHERSON_UID = 23
    const val DONETSK_UID = 28
    const val CRIMEA_UID = 29

    /** The regions on the Black Sea coast, which Kalibr carriers at sea threaten. */
    val BLACK_SEA_COAST_UIDS = listOf(ODESA_UID, MYKOLAIV_UID, KHERSON_UID, ZAPORIZHZHIA_UID)

    /** Occupied since 2014 or 2022: on alert permanently, never targeted by the simulated warnings. */
    val OCCUPIED_REGIONS = setOf(LUHANSK_UID, CRIMEA_UID, Regions.SEVASTOPOL_UID)

    /** Regions whose alerts change: all but the occupied ones. */
    val freeRegionUids: List<Int> = LocationDirectory.oblasts.mapNotNull { it.oblastUid }.filter { it !in OCCUPIED_REGIONS }

    private val regionsByUid = LocationDirectory.oblasts.associateBy { checkNotNull(it.oblastUid) { "Oblast ${it.uid} has no oblastUid" } }

    fun region(uid: Int): AlertLocation = regionsByUid.getValue(uid)

    val settlements: List<Settlement> = Regions.cities
        .filter { it.oblastUid !in OCCUPIED_REGIONS }
        .mapIndexed { index, city ->
            val location = if (city.oblastUid == Regions.KYIV_CITY_UID) {
                region(Regions.KYIV_CITY_UID)
            } else {
                AlertLocation(
                    uid = (FIRST_SETTLEMENT_UID + index).toString(),
                    title = "м. ${ukrainianName(city)}",
                    titleEn = city.name,
                    type = LocationType.City,
                    oblastUid = city.oblastUid,
                    oblastTitle = region(city.oblastUid).title,
                )
            }
            Settlement(city, location)
        }

    private val settlementsByName = settlements.associateBy { it.city.name }

    fun settlement(name: String): Settlement = settlementsByName[name] ?: error("No simulated settlement $name")

    /** Every location the simulation can alert. */
    val all: List<AlertLocation> = (LocationDirectory.oblasts + settlements.map { it.location }).distinctBy { it.uid }

    /**
     * Luhansk oblast and Crimea have been under a standing alert since 2022, as alerts.in.ua shows them
     * (the Luhansk record is the one in its documentation).
     */
    val permanentAlerts: List<AirAlert> = listOf(
        AirAlert(id = 10, location = region(LUHANSK_UID), startedAt = Instant.parse("2022-04-04T16:45:39Z"), notes = "Тимчасово окупована територія"),
        AirAlert(id = 11, location = region(CRIMEA_UID), startedAt = Instant.parse("2022-12-11T00:22:00Z"), notes = "Тимчасово окупована територія"),
    ).map { it.copy(level = ThreatLevel.Red, updatedAt = it.startedAt) }

    /** Ids below it are the standing alerts'; the alerts the simulation opens and closes are numbered from it up. */
    const val FIRST_DYNAMIC_ALERT_ID = 1_000L

    init {
        check(permanentAlerts.all { it.id < FIRST_DYNAMIC_ALERT_ID }) { "Standing alert ids must stay below $FIRST_DYNAMIC_ALERT_ID" }
    }

    private const val FIRST_SETTLEMENT_UID = 1_000

    /** Titles name the city as alerts.in.ua does; the alert's location is found from them by the city's keywords. */
    private fun ukrainianName(city: City): String = UKRAINIAN_NAMES[city.name] ?: city.keywords.first()
}
