# Bracket — předpoklady verze 0.1

1. Podporováno je 2–32 jedinečných hráčů. Pořadí výběru hráčů určuje úvodní páry.
2. Kapacita se předem vyhradí podle počtu hráčů: 2–8 účastníků = 8 míst, 9–16 = 16 míst, 17–32 = 32 míst. Prázdné úvodní pozice zůstávají k dispozici pro pozdě příchozí hráče, dokud jejich zápas není odehrán nebo výslovně potvrzen postup bez zápasu.
3. Vítězná a poraženecká větev se připraví jako acyklický graf. Všechny návaznosti včetně cílových slotů jsou vytvořené před potvrzením prvního výsledku.
4. Z vítězné větve postupuje vítěz do dalšího kola a poražený do poraženecké větve. V poraženecké větvi se poražení dále neposouvají.
5. Zápas s jediným hráčem zůstává čekající. Pořadatel může explicitně potvrdit postup bez zápasu, který nepřidává turnajové body ani ELO.
6. Každý odehraný zápas má právě jednoho vítěze z dvojice hráčů. Remízy nejsou povolené.
7. Výhra se započítá jednou: 2 body. Výhra v rozhodujícím finále přidá dalších 10 bodů. Zápis bodů se při každé změně výsledku sestaví znovu v transakci, takže se neopakují.
8. Při opravě se bracket sestaví znovu v topologickém pořadí. Výsledek navazujícího zápasu se zachová jen tehdy, když se nezměnila ani jedna z jeho hráčských pozic.
9. Finále je v tomto výchozím modelu jednoduché, bez opakovaného resetovacího finále. Změnu tohoto pravidla je potřeba doplnit do plánu grafu a jeho testů.
10. Pořadatel může vyměnit hráče mezi dvěma různými úvodními dvojicemi. Změna vyžaduje potvrzení; výsledky zápasů, ve kterých se účastníci změnili, jsou zrušeny a výsledky bodování i ELO se znovu přepočítají.
11. ELO začíná na 1000, používá K = 32 a vychází ze skutečně odehraných zápasů seřazených podle času. Výsledek bez zápasu rating nemění. Po opravě zápasu se ratingy všech hráčů přehrají z platné historie.
12. Odstoupení zruší pouze budoucí postup hráče. Již odehraný zápas, který obsahuje odstoupivšího hráče, zůstává platný. Neodehraná pozice zůstane prázdná a ostatní seedované pozice se neposouvají.
