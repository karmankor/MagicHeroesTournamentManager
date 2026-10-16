# Magic Heroes Tournament Manager

Nativní aplikace pro Android v Kotlinu, Jetpack Compose a Room. Data zůstávají lokálně v telefonu; aplikace nevyžaduje účet ani server.

## Obsah aktuální verze

- knihovna hráčů s interními ID, úpravou přezdívky a archivací bez mazání historie;
- správa klanů, členství a historického přiřazení bodů;
- vytváření turnajů pro 2–32 hráčů z databáze;
- předem sestavený graf vítězné a poraženecké větve, detail zápasu a potvrzení/opravování výsledků;
- +2 body za odehranou výhru a +10 bonusových bodů navíc za výhru ve finále;
- statistiky, série, žebříčky hráčů/klanů a historie;
- plánované rozšíření: ELO od 1000 bodů, přepočet podle skutečných výsledků a ruční úpravy úvodních dvojic s bezpečným přepočtem návazných zápasů;
- offline PDF export turnaje, hráče a žebříčků přes systémový dialog pro uložení souboru;
- záloha celé lokální databáze do JSON a obnova ze souboru; obnovení nejprve ověří zálohu a přepisuje data v jediné databázové transakci;
- jednotkové testy bodování a plánu bracketu.

## Pravidla bracketu ve verzi 0.1

Implementovaným výchozím modelem je **double elimination** (hráč vypadne po druhé prohře) pro 2–32 hráčů. Účastníci se párují podle pořadí ve výběru: první s druhým, třetí se čtvrtým atd. Návazné zápasy jsou definované dopředu. Vítěz pokračuje vítěznou větví, poražený přechází do odpovídající pozice poraženecké větve. Druhá prohra v poraženecké větvi už dál nenavazuje.

Neúplný úvodní zápas se **nevyhodnotí automaticky**. Pořadatel musí výslovně potvrdit postup bez zápasu; tento postup neposkytuje body za výhru. Bracket je přepočítáván po výsledku i po opravě a starý výsledek navazujícího zápasu se zahodí, pokud se změní jeho hráči.

V této verzi se používá jedno rozhodující velké finále bez dodatečného „reset finále“. Vítěz finále dostane 2 body za výhru a dalších 10 bonusových bodů. Tento model je zvolen jako výchozí implementace; pokud má vaše turnajová varianta jiné pevné rozmístění 32 zápasových pozic, reset finále nebo jiné přesuny poražených, je potřeba upravit plán v `domain/BracketEngine.kt` před ostrým používáním.

## Otevření a sestavení v Android Studiu

1. Nainstaluj aktuální stabilní Android Studio.
2. Rozbal ZIP projektu do složky, kterou si snadno najdeš.
3. V Android Studiu zvol **Open** a vyber složku `MagicHeroesTournamentManager` (tu, ve které jsou `settings.gradle.kts` a `build.gradle.kts`). Neotvírej pouze složku `app`.
4. Při prvním synchronizování projektu povol Android Studiu připojení k internetu, aby stáhl Gradle a knihovny. Samotná aplikace pak funguje offline.
5. V SDK Manageru nainstaluj **Android SDK Platform 36** a vhodné Android SDK Build-Tools. Projekt používá JDK 17; v Android Studiu zkontroluj `Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK` a zvol JBR/JDK 17.
6. Po dokončení Gradle Sync zvol **Build → Make Project**. Testy lze spustit přes panel Gradle: `app → Tasks → verification → testDebugUnitTest`.
7. APK sestavíš přes **Build → Build Bundle(s) / APK(s) → Build APK(s)**. Výstup bude v `app/build/outputs/apk/debug/app-debug.apk`.
8. Přenes APK do telefonu a otevři ho v aplikaci Soubory. Android může vyžadovat jednorázové povolení instalace aplikací z daného zdroje. Bezpečnější alternativou je připojit telefon přes USB, povolit ladění USB a kliknout v Android Studiu na **Run**.

Projektový launcher `gradlew` / `gradlew.bat` používá Gradle 8.13 a při prvním spuštění se jej pokusí stáhnout do lokální cache. Android SDK však musí být nainstalované zvlášť prostřednictvím Android Studia.

## Testování

V kořenové složce projektu:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Na připojeném Android telefonu nebo emulátoru navíc spusť instrumentační testy databáze a opravování výsledků:

```bash
./gradlew connectedDebugAndroidTest
```

Ve Windows použij `gradlew.bat` místo `./gradlew`.

## Struktura

```text
MagicHeroesTournamentManager/
├── app/
│   ├── src/main/java/cz/magicheroes/tournamentmanager/
│   │   ├── data/       # Room entity, DAO, databáze, repository a zálohy
│   │   ├── domain/     # plán bracketu a bodovací pravidla
│   │   ├── export/     # offline PDF export
│   │   ├── ui/         # ViewModel
│   │   └── MainActivity.kt
│   └── src/test/       # jednotkové testy
├── build.gradle.kts
└── settings.gradle.kts
```

## Důležitá poznámka k ověření

Samostatný Kotlin smoke test plánovače bracketu prošel pro všechny počty 2–32 hráčů: ověřil acykličnost grafu a existenci cílů návazností; pro 8 hráčů vytvořil 14 zápasových uzlů a pro 32 hráčů 62. Kód prošel i základní kontrolou syntaktické struktury Kotlinu. To ale nenahrazuje sestavení Android aplikace.

Tato pracovní relace nemá nakonfigurované Android SDK a nedokáže stáhnout Gradle ani Maven/Google Maven závislosti kvůli blokovanému síťovému DNS. Proto zde nebylo možné vytvořit ani ověřit výsledný APK a nešlo spustit Gradle test suite. První skutečné sestavení musí proběhnout v Android Studiu na počítači s nainstalovaným SDK a přístupem k repozitářům. Pokud Gradle vypíše chybu, zkopíruj sem celý první blok chyby; podle něj půjde projekt opravit.
