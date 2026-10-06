Realizzare un'applicazione web didattica che permetta a un cliente di consultare i propri conti, visualizzare i movimenti e simulare trasferimenti tra conti. L'applicazione sarà composta da un frontend Angular, una REST API e un database MySQL.
Per il backend usare Java con Spring Boot. In alternativa, è possibile usare C# con ASP.NET Core, mantenendo gli stessi requisiti funzionali e gli endpoint descritti in questo task.
L'applicazione è esclusivamente dimostrativa: non deve eseguire operazioni bancarie reali né trattare dati o credenziali reali.Stack tecnologico
 Angular
 Java Spring Boot oppure C# ASP.NET Core
 MySQL
 API REST in formato JSON
FunzionalitàAutenticazione e accesso
Consentire l'accesso con credenziali demo.
Proteggere gli endpoint che restituiscono dati personali.
Ogni cliente può consultare solo i propri conti e movimenti.
Conservare le password in forma hash; non salvarle mai in chiaro.
Conti
Mostrare i conti del cliente autenticato.
Visualizzare per ogni conto codice, intestatario, valuta e saldo.
Impedire l'accesso ai conti appartenenti ad altri clienti.
Movimenti
Elencare i movimenti di un conto con data, descrizione, tipo e importo.
Consentire il filtro per conto e intervallo di date.
Ordinare i risultati dal più recente al meno recente.
Trasferimenti simulati
Consentire un trasferimento tra due conti esistenti e distinti.
Accettare solo importi positivi e conti appartenenti al cliente autenticato.
Rifiutare il trasferimento se il saldo disponibile è insufficiente.
Aggiornare il saldo dei due conti e registrare i relativi movimenti nella stessa transazione database: se un passaggio fallisce, nessuna modifica deve essere salvata.
Mostrare all'utente l'esito dell'operazione e gli eventuali errori di validazione.
Modello dati indicativo
 identificativo, email, password hash, ruolo.
 identificativo, codice conto, identificativo del titolare, valuta, saldo.
 identificativo, conto, data e ora, descrizione, tipo, importo e riferimento al trasferimento, se applicabile.
Definire chiavi primarie, chiavi esterne, vincoli e indici adeguati. Usare un tipo decimale esatto per gli importi, evitando float e double.API indicative
POST /api/auth/login: autentica l'utente demo.
GET /api/accounts: restituisce i conti dell'utente autenticato.
GET /api/accounts/{id}/movements: restituisce i movimenti del conto, con filtri opzionali per data.
POST /api/transfers: crea un trasferimento simulato tra due conti autorizzati.
Le API devono validare i dati in ingresso, usare codici HTTP appropriati e restituire errori JSON comprensibili. Documentare gli endpoint e i relativi payload.
Interfaccia Angular
Realizzare le seguenti viste:
Pagina di accesso.
Dashboard con saldi e riepilogo dei conti.
Dettaglio conto con elenco e filtri dei movimenti.
Modulo per inserire e confermare un trasferimento.
Messaggi di caricamento, successo, errore e assenza di risultati.
L'interfaccia deve essere responsive e non deve mostrare dati appartenenti ad altri clienti.
Criteri di accettazione
L'applicazione si avvia seguendo le istruzioni documentate e si collega a MySQL.
Sono disponibili dati demo iniziali, senza credenziali o dati bancari reali.
Un utente autenticato vede solo i propri conti e movimenti.
I filtri dei movimenti restituiscono risultati coerenti con il conto e l'intervallo selezionati.
Un trasferimento valido aggiorna entrambi i saldi e registra i movimenti associati.
Importi invalidi, conti non autorizzati o inesistenti e saldo insufficiente producono un errore senza modifiche parziali al database.
Frontend e backend gestiscono gli errori dell'API senza perdere lo stato dell'interfaccia.
Sono presenti test per i casi principali, in particolare autenticazione, autorizzazioni, validazione e atomicità dei trasferimenti.
Il README descrive prerequisiti, configurazione di MySQL, avvio di backend e frontend e modalità di esecuzione dei test.