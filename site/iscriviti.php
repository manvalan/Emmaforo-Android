<?php
// Raccolta email pre-lancio Kickstarter.
// Salva in un CSV fuori dalla cartella pubblica: ../emmaforo-iscritti/iscritti.csv
// Lo si scarica dal Mac con:
//   scp su233114@access-5021777565.webspace-host.com:emmaforo-iscritti/iscritti.csv .
// e si importa in Brevo (Contatti > Importa).

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

function rispondi($ok, $messaggio, $codice = 200) {
    http_response_code($codice);
    echo json_encode(['ok' => $ok, 'messaggio' => $messaggio]);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    rispondi(false, 'Metodo non consentito', 405);
}

// Campo trappola per i bot: le persone non lo vedono e lo lasciano vuoto.
if (!empty($_POST['sito'])) {
    rispondi(true, 'Grazie!');
}

$email = trim($_POST['email'] ?? '');
$consenso = ($_POST['consenso'] ?? '') === 'si';
$lingua = (($_POST['lingua'] ?? 'it') === 'en') ? 'en' : 'it';
$origine = substr(preg_replace('/[^a-z0-9_-]/i', '', $_POST['origine'] ?? ''), 0, 40);

if (!filter_var($email, FILTER_VALIDATE_EMAIL) || strlen($email) > 254) {
    rispondi(false, $lingua === 'en' ? 'Please check your email address.' : "Controlla l'indirizzo email.", 400);
}
if (!$consenso) {
    rispondi(false, $lingua === 'en' ? 'Please tick the consent box.' : 'Serve il consenso per scriverti.', 400);
}

$cartella = dirname(__DIR__) . '/emmaforo-iscritti';
if (!is_dir($cartella) && !mkdir($cartella, 0700, true)) {
    rispondi(false, 'Errore del server, riprova più tardi.', 500);
}
$file = $cartella . '/iscritti.csv';

$h = fopen($file, 'c+');
if (!$h) {
    rispondi(false, 'Errore del server, riprova più tardi.', 500);
}
flock($h, LOCK_EX);

$email_min = strtolower($email);
$nuovo = filesize($file) === 0;
if (!$nuovo) {
    while (($riga = fgetcsv($h)) !== false) {
        if (isset($riga[0]) && strtolower($riga[0]) === $email_min) {
            flock($h, LOCK_UN);
            fclose($h);
            rispondi(true, $lingua === 'en' ? "You're already on the list. See you at launch!" : 'Sei già in lista. Ci sentiamo al lancio!');
        }
    }
}
fseek($h, 0, SEEK_END);
if ($nuovo) {
    fputcsv($h, ['EMAIL', 'DATA', 'LINGUA', 'ORIGINE', 'CONSENSO']);
}
fputcsv($h, [$email_min, gmdate('Y-m-d H:i:s'), $lingua, $origine, 'si']);
fflush($h);
flock($h, LOCK_UN);
fclose($h);

rispondi(true, $lingua === 'en'
    ? "Done! We'll email you on launch day with the early bird price."
    : 'Fatto! Ti scriviamo il giorno del lancio con il prezzo early bird.');
