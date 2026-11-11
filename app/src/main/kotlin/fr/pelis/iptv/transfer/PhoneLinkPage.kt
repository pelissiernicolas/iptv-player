package fr.pelis.iptv.transfer

/** Page web affichée sur le téléphone après le scan du QR code (aucune ressource externe). */
object PhoneLinkPage {
    fun render(mode: PhoneLinkServer.Mode, code: String): String {
        require(code.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        val content = when (mode) {
            PhoneLinkServer.Mode.LOGIN -> LOGIN
            PhoneLinkServer.Mode.BACKUP -> BACKUP
        }
        return TEMPLATE.replace("{{CONTENT}}", content).replace("{{CODE}}", code)
    }

    private val LOGIN = """
<h1>Ajouter un compte sur la TV</h1>
<p class="hint">Saisissez vos identifiants Xtream Codes, ou collez votre lien M3U complet dans le champ serveur.</p>
<form id="f" autocomplete="off">
  <label>Nom du profil (facultatif)<input name="name" placeholder="Salon, Abonnement 2…"></label>
  <label>URL du serveur ou lien M3U<input name="server" inputmode="url" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="http://exemple.com:8080" required></label>
  <label>Nom d'utilisateur<input name="username" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
  <label>Mot de passe<input name="password" type="password"></label>
  <button type="submit">Envoyer à la TV</button>
</form>
<p id="msg" role="status"></p>
<p class="note">Les identifiants sont envoyés directement à la TV par votre réseau Wi-Fi, sans passer par Internet.</p>
<script>
const f = document.getElementById('f');
f.addEventListener('submit', async (e) => {
  e.preventDefault();
  const d = Object.fromEntries(new FormData(f));
  await send('/api/login', d, f);
});
</script>
"""

    private val BACKUP = """
<h1>Sauvegarde de la TV</h1>
<h2>Envoyer une sauvegarde vers la TV</h2>
<p class="hint">Choisissez un fichier exporté depuis IPTV Player (menu Compte → « Exporter favoris et historique »). Il est ajouté au profil ouvert sur la TV, sans rien supprimer.</p>
<form id="f">
  <input id="file" type="file" accept=".json,application/json" required>
  <button type="submit">Envoyer à la TV</button>
</form>
<p id="msg" role="status"></p>
<h2>Récupérer la sauvegarde de la TV</h2>
<p class="hint">Favoris, lectures en cours et chaînes récentes du profil ouvert sur la TV.</p>
<a class="button" href="/api/export?c={{CODE}}">Télécharger la sauvegarde</a>
<script>
const f = document.getElementById('f');
f.addEventListener('submit', async (e) => {
  e.preventDefault();
  const file = document.getElementById('file').files[0];
  if (!file) return;
  if (file.size > 4 * 1024 * 1024) { show(false, 'Fichier trop volumineux.'); return; }
  await send('/api/backup', { backup: await file.text() }, f);
});
</script>
"""

    private val TEMPLATE = """<!doctype html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>IPTV Player</title>
<style>
:root { color-scheme: dark; }
body { margin: 0; font: 16px/1.45 system-ui, -apple-system, Roboto, sans-serif; background: #0b1120; color: #e5e9f0; }
main { max-width: 480px; margin: 0 auto; padding: 24px 16px 48px; }
h1 { font-size: 1.4rem; margin: 0 0 8px; }
h2 { font-size: 1.1rem; margin: 28px 0 4px; }
.hint, .note { color: #9aa4b5; font-size: .92rem; }
.note { margin-top: 24px; }
label { display: block; margin: 14px 0 0; font-size: .9rem; color: #c3cad6; }
input { display: block; box-sizing: border-box; width: 100%; margin-top: 6px; padding: 12px; font-size: 1rem;
  border-radius: 10px; border: 1px solid #334155; background: #111a2e; color: inherit; }
input[type=file] { padding: 10px; }
button, .button { display: block; box-sizing: border-box; width: 100%; margin-top: 20px; padding: 14px; border: 0;
  border-radius: 999px; background: #4fb3f6; color: #04121f; font-size: 1rem; font-weight: 600; text-align: center;
  text-decoration: none; cursor: pointer; }
button[disabled] { opacity: .6; }
#msg { min-height: 1.4em; margin-top: 16px; font-weight: 600; }
.ok { color: #6fdc8c; } .err { color: #ff8a80; }
</style></head>
<body><main>
{{CONTENT}}
</main>
<script>
const CODE = '{{CODE}}';
function show(ok, text) {
  const m = document.getElementById('msg');
  m.textContent = text;
  m.className = ok ? 'ok' : 'err';
}
async function send(path, data, form) {
  const button = form.querySelector('button');
  button.disabled = true;
  show(true, 'Envoi en cours…');
  try {
    const r = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(Object.assign({ c: CODE }, data)) });
    let reply;
    try { reply = await r.json(); } catch (_) { reply = { ok: false, message: await r.text() }; }
    show(reply.ok, reply.message);
    if (reply.ok) { form.reset(); return; }
  } catch (_) {
    show(false, 'La TV ne répond plus. Vérifiez que l\'écran du QR code est toujours affiché.');
  }
  button.disabled = false;
}
</script>
</body></html>
"""
}
