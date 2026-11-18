// Processus de fond : uniquement le verrouillage automatique (alarme), au lot 2.
import { api, extensionVault, LOCK_ALARM } from './lib/extension.js';

const vault = extensionVault();

api.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === LOCK_ALARM) vault.expireIfDue();
});
