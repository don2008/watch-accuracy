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

## Katalóg značiek

Súbor `app/src/main/java/sk/watchaccuracy/WatchCatalog.kt` obsahuje 101 značiek a 498 názvov modelových radov vrátane nezávislých značiek a microbrandov. Pri pridávaní hodiniek sa dá značka aj model vyhľadať alebo vybrať z posúvateľného zoznamu. Obe polia zostávajú voľne upraviteľné, takže je možné doplniť konkrétnu referenciu, starší model alebo značku mimo katalógu. Modely sa filtrujú podľa vybranej značky; pri vlastnej značke zostane model ručne zadateľný. Výber je dostupný offline a neovplyvňuje uložené hodinky.

Záznamy sú názvy rodín a vybraných modelov, nie úplný zoznam referencií či ponuka aktuálne dostupných hodiniek. Pri zostavení boli použité napríklad oficiálne katalógy [Biatec](https://www.biatecwatches.com/watches), [Venezianico](https://www.venezianico.com/collections), [Farer](https://farer.com/collections/), [Lorier](https://www.lorierwatches.com/collections), [Traska](https://www.traskawatch.com/collections), [DWISS](https://www.dwiss.com/collections), [Orion](https://orionwatch.com/) a [Phoibos](https://phoiboswatch.com/categories/collection.html). Katalóg zahŕňa aj historické modely.
