package com.gusak.lab.radarnebo.geo

data class GeoPoint(val lat: Double, val lon: Double)

data class Oblast(val uid: Int, val nameUk: String, val nameEn: String)

data class City(val name: String, val oblastUid: Int, val point: GeoPoint, val keywords: List<String>)

object Regions {
    const val KYIV_CITY_UID = 31
    const val SEVASTOPOL_UID = 30

    val oblasts: List<Oblast> = listOf(
        Oblast(3, "Хмельницька область", "Khmelnytskyi Oblast"),
        Oblast(4, "Вінницька область", "Vinnytsia Oblast"),
        Oblast(5, "Рівненська область", "Rivne Oblast"),
        Oblast(8, "Волинська область", "Volyn Oblast"),
        Oblast(9, "Дніпропетровська область", "Dnipropetrovsk Oblast"),
        Oblast(10, "Житомирська область", "Zhytomyr Oblast"),
        Oblast(11, "Закарпатська область", "Zakarpattia Oblast"),
        Oblast(12, "Запорізька область", "Zaporizhzhia Oblast"),
        Oblast(13, "Івано-Франківська область", "Ivano-Frankivsk Oblast"),
        Oblast(14, "Київська область", "Kyiv Oblast"),
        Oblast(15, "Кіровоградська область", "Kirovohrad Oblast"),
        Oblast(16, "Луганська область", "Luhansk Oblast"),
        Oblast(17, "Миколаївська область", "Mykolaiv Oblast"),
        Oblast(18, "Одеська область", "Odesa Oblast"),
        Oblast(19, "Полтавська область", "Poltava Oblast"),
        Oblast(20, "Сумська область", "Sumy Oblast"),
        Oblast(21, "Тернопільська область", "Ternopil Oblast"),
        Oblast(22, "Харківська область", "Kharkiv Oblast"),
        Oblast(23, "Херсонська область", "Kherson Oblast"),
        Oblast(24, "Черкаська область", "Cherkasy Oblast"),
        Oblast(25, "Чернігівська область", "Chernihiv Oblast"),
        Oblast(26, "Чернівецька область", "Chernivtsi Oblast"),
        Oblast(27, "Львівська область", "Lviv Oblast"),
        Oblast(28, "Донецька область", "Donetsk Oblast"),
        Oblast(29, "Автономна Республіка Крим", "Crimea"),
        Oblast(SEVASTOPOL_UID, "м. Севастополь", "Sevastopol"),
        Oblast(KYIV_CITY_UID, "м. Київ", "Kyiv City"),
    )

    private val oblastsByUid = oblasts.associateBy { it.uid }

    fun oblast(uid: Int): Oblast? = oblastsByUid[uid]

    /** Major cities, used both as favorite presets and to geolocate city/hromada level alerts. */
    val cities: List<City> = listOf(
        City("Kyiv", KYIV_CITY_UID, GeoPoint(50.4501, 30.5234), listOf("Київ")),
        City("Kharkiv", 22, GeoPoint(49.9935, 36.2304), listOf("Харків")),
        City("Odesa", 18, GeoPoint(46.4825, 30.7233), listOf("Одес")),
        City("Dnipro", 9, GeoPoint(48.4647, 35.0462), listOf("Дніпро", "Дніпровськ")),
        City("Lviv", 27, GeoPoint(49.8397, 24.0297), listOf("Львів")),
        City("Zaporizhzhia", 12, GeoPoint(47.8388, 35.1396), listOf("Запоріж")),
        City("Kryvyi Rih", 9, GeoPoint(47.9105, 33.3918), listOf("Кривий Ріг", "Криворіз")),
        City("Mykolaiv", 17, GeoPoint(46.9750, 31.9946), listOf("Миколаїв")),
        City("Vinnytsia", 4, GeoPoint(49.2331, 28.4682), listOf("Вінниц")),
        City("Kherson", 23, GeoPoint(46.6354, 32.6169), listOf("Херсон")),
        City("Poltava", 19, GeoPoint(49.5883, 34.5514), listOf("Полтав")),
        City("Chernihiv", 25, GeoPoint(51.4982, 31.2893), listOf("Чернігів")),
        City("Cherkasy", 24, GeoPoint(49.4444, 32.0598), listOf("Черкас")),
        City("Sumy", 20, GeoPoint(50.9077, 34.7981), listOf("Сум")),
        City("Zhytomyr", 10, GeoPoint(50.2547, 28.6587), listOf("Житомир")),
        City("Khmelnytskyi", 3, GeoPoint(49.4229, 26.9871), listOf("Хмельницьк")),
        City("Rivne", 5, GeoPoint(50.6199, 26.2516), listOf("Рівн")),
        City("Kropyvnytskyi", 15, GeoPoint(48.5079, 32.2623), listOf("Кропивницьк")),
        City("Ivano-Frankivsk", 13, GeoPoint(48.9226, 24.7111), listOf("Івано-Франківськ")),
        City("Ternopil", 21, GeoPoint(49.5535, 25.5948), listOf("Тернопіл")),
        City("Lutsk", 8, GeoPoint(50.7472, 25.3254), listOf("Луцьк")),
        City("Uzhhorod", 11, GeoPoint(48.6208, 22.2879), listOf("Ужгород")),
        City("Chernivtsi", 26, GeoPoint(48.2915, 25.9403), listOf("Чернівц")),
        City("Kramatorsk", 28, GeoPoint(48.7389, 37.5848), listOf("Краматорськ")),
        City("Sloviansk", 28, GeoPoint(48.8530, 37.6053), listOf("Слов'янськ", "Словʼянськ")),
        City("Kremenchuk", 19, GeoPoint(49.0670, 33.4204), listOf("Кременчу")),
        City("Bila Tserkva", 14, GeoPoint(49.7968, 30.1311), listOf("Біла Церква", "Білоцерків")),
        City("Brovary", 14, GeoPoint(50.5110, 30.7909), listOf("Бровар")),
        City("Pavlohrad", 9, GeoPoint(48.5350, 35.8700), listOf("Павлоград")),
        City("Nikopol", 9, GeoPoint(47.5667, 34.4000), listOf("Нікопол")),
        City("Izmail", 18, GeoPoint(45.3516, 28.8365), listOf("Ізмаїл")),
        City("Shostka", 20, GeoPoint(51.8667, 33.4833), listOf("Шостк")),
        City("Konotop", 20, GeoPoint(51.2403, 33.2026), listOf("Конотоп")),
        City("Kupiansk", 22, GeoPoint(49.7106, 37.6156), listOf("Куп'янськ", "Купʼянськ")),
        City("Sevastopol", SEVASTOPOL_UID, GeoPoint(44.6166, 33.5254), listOf("Севастопол")),
        City("Nizhyn", 25, GeoPoint(51.0480, 31.8869), listOf("Ніжин")),
        City("Berestyn", 22, GeoPoint(49.3747, 35.4436), listOf("Берестин", "Красноград")),
        City("Voznesensk", 17, GeoPoint(47.5679, 31.3329), listOf("Вознесенськ")),
        City("Pervomaisk", 17, GeoPoint(48.0440, 30.8506), listOf("Первомайськ")),
        City("Uman", 24, GeoPoint(48.7484, 30.2218), listOf("Умань", "Уман")),
        City("Haisyn", 4, GeoPoint(48.8125, 29.3897), listOf("Гайсин")),
        // Smaller towns, so predictions can name the settlement actually on a drone's way (approximate centres).
        City("Pryluky", 25, GeoPoint(50.5931, 32.3876), listOf("Прилук")),
        City("Bakhmach", 25, GeoPoint(51.1808, 32.8302), listOf("Бахмач")),
        City("Novhorod-Siverskyi", 25, GeoPoint(51.9874, 33.2620), listOf("Новгород-Сіверськ")),
        City("Koriukivka", 25, GeoPoint(51.7720, 32.2560), listOf("Корюків")),
        City("Okhtyrka", 20, GeoPoint(50.3104, 34.8988), listOf("Охтир")),
        City("Romny", 20, GeoPoint(50.7515, 33.4746), listOf("Ромн", "Ромен")),
        City("Lebedyn", 20, GeoPoint(50.5872, 34.4846), listOf("Лебедин")),
        City("Hlukhiv", 20, GeoPoint(51.6780, 33.9120), listOf("Глух")),
        City("Trostianets", 20, GeoPoint(50.4810, 34.9650), listOf("Тростян")),
        City("Chuhuiv", 22, GeoPoint(49.8353, 36.6880), listOf("Чугу")),
        City("Izium", 22, GeoPoint(49.2128, 37.2569), listOf("Ізюм")),
        City("Lozova", 22, GeoPoint(48.8890, 36.3170), listOf("Лозов")),
        City("Bohodukhiv", 22, GeoPoint(50.1650, 35.5270), listOf("Богодух")),
        City("Balakliia", 22, GeoPoint(49.4627, 36.8597), listOf("Балакл")),
        City("Zolochiv", 22, GeoPoint(50.2790, 35.9780), listOf("Золочів")),
        City("Lubny", 19, GeoPoint(50.0186, 32.9869), listOf("Лубн", "Лубен")),
        City("Myrhorod", 19, GeoPoint(49.9688, 33.6087), listOf("Миргород")),
        City("Hadiach", 19, GeoPoint(50.3713, 33.9907), listOf("Гадяч")),
        City("Horishni Plavni", 19, GeoPoint(49.0107, 33.6440), listOf("Горішні Плавні", "Горішньоплавн")),
        City("Boryspil", 14, GeoPoint(50.3527, 30.9550), listOf("Борисп")),
        City("Fastiv", 14, GeoPoint(50.0750, 29.9180), listOf("Фастів", "Фастов")),
        City("Vasylkiv", 14, GeoPoint(50.1770, 30.3210), listOf("Васильк")),
        City("Obukhiv", 14, GeoPoint(50.1072, 30.6180), listOf("Обух")),
        City("Irpin", 14, GeoPoint(50.5218, 30.2506), listOf("Ірпін")),
        City("Bucha", 14, GeoPoint(50.5435, 30.2190), listOf("Буча", "Бучан")),
        City("Pereiaslav", 14, GeoPoint(50.0650, 31.4450), listOf("Переяслав")),
        City("Yahotyn", 14, GeoPoint(50.2790, 31.7650), listOf("Яготин")),
        City("Smila", 24, GeoPoint(49.2224, 31.8870), listOf("Сміл")),
        City("Zolotonosha", 24, GeoPoint(49.6680, 32.0400), listOf("Золотонош")),
        City("Kaniv", 24, GeoPoint(49.7518, 31.4600), listOf("Канів", "Канев")),
        City("Oleksandriia", 15, GeoPoint(48.6696, 33.1159), listOf("Олександрі")),
        City("Znamianka", 15, GeoPoint(48.7150, 32.6730), listOf("Знам'ян", "Знамʼян")),
        City("Kamianske", 9, GeoPoint(48.5116, 34.6021), listOf("Кам'янськ", "Камʼянськ")),
        City("Samar", 9, GeoPoint(48.6333, 35.2167), listOf("Самар", "Новомосковськ")),
        City("Marhanets", 9, GeoPoint(47.6450, 34.6280), listOf("Марган")),
        City("Zhovti Vody", 9, GeoPoint(48.3456, 33.5023), listOf("Жовті Води", "Жовтовод")),
        City("Bashtanka", 17, GeoPoint(47.4070, 32.4380), listOf("Баштан")),
        City("Pivdennoukrainsk", 17, GeoPoint(47.8167, 31.1800), listOf("Південноукраїнськ", "Южноукраїнськ")),
        City("Chornomorsk", 18, GeoPoint(46.3017, 30.6569), listOf("Чорноморськ")),
        City("Bilhorod-Dnistrovskyi", 18, GeoPoint(46.1871, 30.3486), listOf("Білгород-Дністровськ")),
        City("Podilsk", 18, GeoPoint(47.7470, 29.5330), listOf("Подільськ")),
        City("Pivdenne", 18, GeoPoint(46.6222, 31.1013), listOf("Південне", "Южне")),
        City("Zhmerynka", 4, GeoPoint(49.0380, 28.1100), listOf("Жмерин")),
        City("Koziatyn", 4, GeoPoint(49.7160, 28.8360), listOf("Козятин")),
        City("Khmilnyk", 4, GeoPoint(49.5570, 27.9570), listOf("Хмільник")),
        City("Ladyzhyn", 4, GeoPoint(48.6840, 29.2360), listOf("Ладижин")),
        City("Berdychiv", 10, GeoPoint(49.8990, 28.6020), listOf("Бердич")),
        City("Korosten", 10, GeoPoint(50.9510, 28.6380), listOf("Коростен")),
        City("Zviahel", 10, GeoPoint(50.5880, 27.6160), listOf("Звягел", "Новоград-Волинськ")),
        City("Kamianets-Podilskyi", 3, GeoPoint(48.6845, 26.5856), listOf("Кам'янець-Подільськ", "Камʼянець-Подільськ")),
        City("Shepetivka", 3, GeoPoint(50.1840, 27.0630), listOf("Шепетів")),
        City("Starokostiantyniv", 3, GeoPoint(49.7550, 27.2060), listOf("Старокостянтинів")),
        City("Drohobych", 27, GeoPoint(49.3500, 23.5050), listOf("Дрогобич")),
        City("Stryi", 27, GeoPoint(49.2610, 23.8500), listOf("Стрий", "Стрийськ")),
        City("Sheptytskyi", 27, GeoPoint(50.3870, 24.2290), listOf("Шептицьк", "Червоноград")),
        City("Dubno", 5, GeoPoint(50.4170, 25.7340), listOf("Дубн")),
        City("Varash", 5, GeoPoint(51.3500, 25.8500), listOf("Вараш")),
        City("Kovel", 8, GeoPoint(51.2150, 24.7090), listOf("Ковел")),
        City("Volodymyr", 8, GeoPoint(50.8480, 24.3220), listOf("Володимир")),
    )

    /** Rough center point for an oblast-level region, including the two city regions without polygons. */
    fun center(uid: Int): GeoPoint? = when (uid) {
        KYIV_CITY_UID -> cities.first { it.oblastUid == KYIV_CITY_UID }.point
        SEVASTOPOL_UID -> cities.first { it.oblastUid == SEVASTOPOL_UID }.point
        else -> UkraineGeometry.oblasts.firstOrNull { it.uid == uid }?.let { GeoPoint(it.centerLat, it.centerLon) }
    }

    /** Finds the city named in an alert title, preferring cities in [oblastUid] when given (names repeat across regions). */
    fun cityForTitle(title: String, oblastUid: Int? = null): City? = cities
        .filter { oblastUid == null || it.oblastUid == oblastUid }
        .firstOrNull { city -> city.keywords.any { title.contains(it, ignoreCase = true) } }

    /**
     * Finds the oblast containing [point]. Kyiv city is tested first since it sits inside Kyiv oblast.
     * Points at a known settlement take that settlement's oblast: the simplified polygons can put
     * border and riverside towns (e.g. Kherson on the Dnipro) on the wrong side or outside every oblast.
     */
    fun oblastAt(point: GeoPoint): Int? {
        if (GeoMath.distanceKm(point, center(KYIV_CITY_UID)!!) < 14.0) return KYIV_CITY_UID
        if (GeoMath.distanceKm(point, center(SEVASTOPOL_UID)!!) < 12.0) return SEVASTOPOL_UID
        cities.firstOrNull { GeoMath.distanceKm(point, it.point) < SETTLEMENT_RADIUS_KM }?.let { return it.oblastUid }
        return oblastByPolygon(point)
    }

    /** The city (any settlement with city status) closest to [point], and how far away it is in km. */
    fun nearestCity(point: GeoPoint): Pair<MapCity, Double> = UkraineCities.all
        .map { it to GeoMath.distanceKm(point, GeoPoint(it.lat.toDouble(), it.lon.toDouble())) }
        .minBy { it.second }

    /** Polygon-only lookup, without the settlement shortcut. */
    fun oblastByPolygon(point: GeoPoint): Int? = UkraineGeometry.oblasts.firstOrNull { GeoMath.contains(it.lonLat, point) }?.uid

    private const val SETTLEMENT_RADIUS_KM = 4.0
}
