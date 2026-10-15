# Bracket — předpoklady verze 0.1

1. Podporováno je 2–32 jedinečných hráčů. Pořadí výběru hráčů určuje úvodní páry.
2. Pro více než dva hráče se připraví vítězná a poraženecká větev jako acyklický graf. Všechny návaznosti včetně cílových slotů jsou vytvořené před potvrzením prvního výsledku.
3. Z vítězné větve postupuje vítěz do dalšího kola a poražený do poraženecké větve. V poraženecké větvi se poražení dále neposouvají.
4. Zápas s jediným hráčem zůstává čekající. Pořadatel může explicitně potvrdit postup bez zápasu, který nepřidává body.
5. Každý odehraný zápas má právě jednoho vítěze z dvojice hráčů. Remízy nejsou povolené.
6. Výhra se započítá jednou: 2 body. Výhra v rozhodujícím finále přidá dalších 10 bodů. Zápis bodů se při každé změně výsledku sestaví znovu v transakci, takže se neopakují.
7. Při opravě se bracket sestaví znovu v topologickém pořadí. Výsledek navazujícího zápasu se zachová jen tehdy, když se nezměnila ani jedna z jeho hráčských pozic.
8. Finále je v tomto výchozím modelu jednoduché, bez opakovaného resetovacího finále. Změnu tohoto pravidla je potřeba doplnit do plánu grafu a jeho testů.
