// Copie avec effacement : le presse-papiers est vidé au bout de 30 s s'il contient encore la même
// valeur et si la page est toujours ouverte (une popup fermée ne peut plus rien effacer).
export const CLEAR_MS = 30_000;

export async function copySecret(value) {
  await navigator.clipboard.writeText(value);
  setTimeout(async () => {
    try {
      if ((await navigator.clipboard.readText()) === value) await navigator.clipboard.writeText('');
    } catch {
      // Lecture refusée (page sans le focus…) : on n'écrase pas à l'aveugle ce que l'utilisateur a pu copier depuis.
    }
  }, CLEAR_MS);
}
