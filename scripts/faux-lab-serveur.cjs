// Lance le faux lab (scripts/faux-lab.cjs) comme processus autonome, pour les tests de l'application
// Android : écrit son adresse sur la sortie standard, s'arrête quand l'entrée standard se ferme.
// L'adresse de retour acceptée est celle de l'application : <adresse du faux lab>/coffre-retour/.
const { start } = require('./faux-lab.cjs');

(async () => {
  const redirectUris = [];
  const lab = await start({ redirectUris });
  redirectUris.push(`${lab.url}/coffre-retour/`);
  process.stdout.write(`${lab.url}\n`);
  process.stdin.on('end', () => { lab.close(); process.exit(0); });
  process.stdin.resume();
})();
