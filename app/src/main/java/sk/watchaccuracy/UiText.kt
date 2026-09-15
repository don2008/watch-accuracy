package sk.watchaccuracy

data class UiText(
    val watches: String, val settings: String, val addWatch: String, val noWatches: String,
    val measurements: String, val firstMeasurement: String, val lastDeviation: String,
    val newMeasurement: String, val measurementHistory: String, val photo: String, val dial: String,
    val brand: String, val model: String, val saveWatch: String, val recentBrands: String,
    val chooseBrand: String, val chooseModel: String, val dialShape: String, val shapeHelp: String,
    val round: String, val square: String, val rectangle: String, val continueCamera: String,
    val cameraPermission: String, val cameraGuide: String, val flash: String, val close: String,
    val measurementCheck: String, val photoTime: String, val dialTime: String, val hours: String,
    val minutes: String, val seconds: String, val saveMeasurement: String, val recordDetail: String,
    val calculatedDeviation: String, val measurementDate: String, val first: String,
    val environment: String, val language: String, val colorCombination: String, val classic: String,
    val blue: String, val monochrome: String, val permissions: String, val camera: String,
    val cameraReason: String, val photos: String, val photosReason: String, val reminders: String,
    val remindersReason: String, val back: String, val secondsPerDay: String,
    val layout: String, val detectedLayout: String, val detectionConfidence: String,
    val layoutClassic: String, val layoutGmt: String, val layoutSmallSeconds: String, val layoutRegulator: String, val layoutJumpHour: String,
    val shutterPosition: String, val left: String, val center: String, val right: String,
    val manualAdjust: String, val tapCenter: String, val tapHour: String, val tapMinute: String, val tapSecond: String, val resetPoints: String
)

fun uiText(language: String) = if (language == "en") UiText(
    watches="Watches", settings="Settings", addWatch="Add watch", noWatches="No watches saved yet",
    measurements="measurements", firstMeasurement="first measurement", lastDeviation="Latest daily deviation",
    newMeasurement="New measurement", measurementHistory="Measurement history", photo="Photo", dial="dial",
    brand="Brand", model="Model or type", saveWatch="Save watch", recentBrands="Recently used brands",
    chooseBrand="Choose or enter a brand", chooseModel="Choose or enter a model", dialShape="Dial shape",
    shapeHelp="Choose the template that best matches your watch.", round="Round", square="Square", rectangle="Rectangle",
    continueCamera="Continue to camera", cameraPermission="Camera permission is required", cameraGuide="Align 12 o'clock with the 12 marker and the hand axis with the centre cross",
    flash="Flash", close="Close", measurementCheck="Check measurement", photoTime="Photo capture time", dialTime="Time read from dial",
    hours="hours", minutes="minutes", seconds="seconds", saveMeasurement="Save measurement", recordDetail="Measurement detail",
    calculatedDeviation="Calculated daily deviation", measurementDate="Measurement date", first="First measurement",
    environment="Interface", language="Language", colorCombination="Color combination", classic="Classic", blue="Blue",
    monochrome="Monochrome", permissions="Permissions", camera="Camera", cameraReason="Required for measurements",
    photos="Photos", photosReason="Dial images stay inside the app", reminders="Reminders", remindersReason="Reminder for the next measurement", back="Back", secondsPerDay="s/day",
    layout="Dial layout", detectedLayout="Detected layout", detectionConfidence="Dial type confidence",
    layoutClassic="Classic", layoutGmt="GMT / second time zone", layoutSmallSeconds="Small seconds", layoutRegulator="Regulator", layoutJumpHour="Jump hour",
    shutterPosition="Shutter button position", left="Left", center="Center", right="Right",
    manualAdjust="Precise hand marking", tapCenter="Tap the centre pinion", tapHour="Tap the tip of the hour hand", tapMinute="Tap the tip of the minute hand", tapSecond="Tap the tip of the seconds hand", resetPoints="Mark again"
) else UiText(
    watches="Hodinky", settings="Nastavenia", addWatch="Pridať hodinky", noWatches="Zatiaľ nemáte uložené žiadne hodinky",
    measurements="meraní", firstMeasurement="prvé meranie", lastDeviation="Posledná denná odchýlka",
    newMeasurement="Nové meranie", measurementHistory="História meraní", photo="Fotka", dial="ciferník",
    brand="Značka", model="Model alebo typ", saveWatch="Uložiť hodinky", recentBrands="Naposledy použité značky",
    chooseBrand="Vyberte alebo zadajte značku", chooseModel="Vyberte alebo zadajte model", dialShape="Tvar ciferníka",
    shapeHelp="Vyberte šablónu, ktorá najlepšie zodpovedá tvaru vašich hodiniek.", round="Kruhový", square="Štvorcový", rectangle="Obdĺžnikový",
    continueCamera="Pokračovať k fotoaparátu", cameraPermission="Fotoaparát potrebuje povolenie", cameraGuide="Zarovnajte 12. hodinu so značkou 12 a os ručičiek so stredovým krížom",
    flash="Blesk", close="Zavrieť", measurementCheck="Kontrola merania", photoTime="Čas vytvorenia fotografie", dialTime="Čas odčítaný z ciferníka",
    hours="hodiny", minutes="minúty", seconds="sekundy", saveMeasurement="Uložiť meranie", recordDetail="Detail merania",
    calculatedDeviation="Vypočítaná denná odchýlka", measurementDate="Dátum merania", first="Prvé meranie",
    environment="Prostredie", language="Jazyk", colorCombination="Farebná kombinácia", classic="Klasická", blue="Modrá",
    monochrome="Čiernobiela", permissions="Povolenia", camera="Fotoaparát", cameraReason="Potrebný na nové merania",
    photos="Fotografie", photosReason="Snímky sa ukladajú iba v aplikácii", reminders="Pripomienky", remindersReason="Pripomenutie ďalšieho merania", back="Späť", secondsPerDay="s/deň",
    layout="Typ ciferníka", detectedLayout="Rozpoznaný typ", detectionConfidence="Istota rozpoznania typu",
    layoutClassic="Klasický", layoutGmt="GMT / druhé časové pásmo", layoutSmallSeconds="Malá sekundovka", layoutRegulator="Regulátor", layoutJumpHour="Skoková hodina",
    shutterPosition="Poloha tlačidla spúšte", left="Vľavo", center="V strede", right="Vpravo",
    manualAdjust="Presné označenie ručičiek", tapCenter="Ťuknite na stred osi ručičiek", tapHour="Ťuknite na koniec hodinovej ručičky", tapMinute="Ťuknite na koniec minútovej ručičky", tapSecond="Ťuknite na koniec sekundovej ručičky", resetPoints="Označiť znova"
)

val knownModels = linkedMapOf(
    "Rolex" to listOf("Oyster Perpetual 41", "Submariner", "Datejust", "Explorer", "GMT-Master II"),
    "Omega" to listOf("Speedmaster", "Seamaster", "Constellation", "De Ville"),
    "TAG Heuer" to listOf("Monaco", "Carrera", "Aquaracer", "Formula 1"),
    "Jaeger-LeCoultre" to listOf("Reverso", "Master Control", "Polaris"),
    "Seiko" to listOf("Presage", "Prospex", "Seiko 5 Sports", "Astron"),
    "Tissot" to listOf("PRX", "Le Locle", "Gentleman", "Seastar"),
    "Longines" to listOf("Spirit", "HydroConquest", "Master Collection", "DolceVita"),
    "Hamilton" to listOf("Khaki Field", "Jazzmaster", "Ventura"),
    "Citizen" to listOf("Tsuyosa", "Promaster", "Series 8"),
    "Orient" to listOf("Bambino", "Kamasu", "Mako")
)
