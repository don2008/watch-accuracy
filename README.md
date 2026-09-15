# Presnosť hodiniek

Natívna Android aplikácia v Kotlin + Jetpack Compose na sledovanie dennej odchýlky mechanických hodiniek.

## Funkcie prvej verzie

- zoznam hodiniek a pridávanie značky/modelu,
- história a detail meraní,
- kruhová, štvorcová a obdĺžniková šablóna fotoaparátu,
- presná časová značka vytvorená pri stlačení spúšte,
- základný lokálny odhad polohy ručičiek s povinnou kontrolou používateľom,
- výpočet odchýlky v sekundách za deň,
- lokálne uloženie údajov a fotografií,
- slovenčina/angličtina a tri farebné schémy v nastaveniach.

## Otvorenie

1. Otvorte priečinok `watch-accuracy-android` v aktuálnom Android Studio.
2. Nechajte Android Studio dokončiť Gradle Sync a nainštalovať SDK 35, ak ho ešte nemáte.
3. Spustite aplikáciu na fyzickom telefóne s Androidom 8.0 alebo novším.

Automatické čítanie ručičiek je zámerne len prvý odhad. Pri odleskoch, subciferníkoch alebo neobvyklých ručičkách treba čas na kontrolnej obrazovke opraviť.
