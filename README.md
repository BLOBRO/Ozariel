# Ozariel

Un agent IA personnel pour Android. Tu lui parles, il agit sur ton téléphone :

- ouvrir des applications et **naviguer dedans** (lire l'écran, toucher, écrire, faire défiler) ;
- **contrôler la musique** (lecture, pause, suivant, volume, lancer un artiste ou une playlist) ;
- **programmer des tâches** (« tous les matins à 7h, mets ma playlist Réveil ») ;
- lire tes messages (WhatsApp, SMS, Telegram, Messenger…) et **y répondre dans ton style**, appris à partir de tes vraies conversations.

Le cerveau est [Claude](https://www.anthropic.com/claude), appelé avec ta propre clé API.

## Installer l'app

1. Sur GitHub, ouvre l'onglet **Actions**, clique sur la dernière construction réussie « Construire l'APK », puis télécharge **ozariel-apk** (en bas de la page).
2. Décompresse le fichier et ouvre `app-debug.apk` sur ton téléphone. Android te demandera d'autoriser l'installation depuis cette source.
3. Ouvre Ozariel, va dans **Réglages** et colle ta clé API Claude (à créer sur [console.anthropic.com](https://console.anthropic.com)).
4. Toujours dans **Réglages**, active :
   - **Pilote d'applications** : Réglages Android > Accessibilité > Ozariel ;
   - **Messages et musique** (accès aux notifications) ;
   - **Afficher des notifications**.

   Sur Android 13 et plus, si une option est grisée (« paramètre restreint »), va dans Réglages > Applications > Ozariel > ⋮ > **Autoriser les paramètres restreints**, puis réessaie.

## Lui apprendre ton style

1. Dans WhatsApp, ouvre une discussion, menu ⋮ > Plus > **Exporter la discussion** > **Sans médias**.
2. Fais-le pour plusieurs personnes différentes (famille, amis, travail).
3. Dans Ozariel, onglet **Mon style**, importe les fichiers puis appuie sur **Analyser mon style**.

Ensuite, à chaque message reçu, Ozariel te propose une réponse (bouton **Envoyer** dans la notification). Pour qu'il réponde tout seul, active **Réponses automatiques** dans les Réglages, puis choisis les contacts concernés dans l'onglet **Messages**. Il ne répond jamais seul dans les groupes ni quand la réponse demande une décision de ta part (rendez-vous, argent, sujet sensible) : il te prévient à la place.

## Exemples de demandes

- « Mets du lo-fi sur Spotify et monte le volume à 60 % »
- « Ouvre YouTube et cherche une recette de crêpes »
- « Qu'est-ce que Léo m'a écrit ? Réponds-lui que j'arrive dans 10 minutes »
- « Réveille-moi demain à 7h »
- « Tous les soirs à 23h, mets le volume à 10 % »

## Sécurité et vie privée

- Ta clé API et tes réglages sont chiffrés sur le téléphone.
- Ta mémoire de style (tes réponses passées) reste sur le téléphone. Seuls les extraits utiles à une réponse sont envoyés à Claude.
- Envoyer un message demande toujours ta confirmation, sauf pour les contacts en réponse automatique, ou si tu l'autorises explicitement pour les tâches programmées.
- L'agent refuse les paiements, achats, suppressions de données et changements de mot de passe.

## Pour les développeurs

- `core/` : le cerveau en Kotlin pur (boucle d'agent avec le SDK Java d'Anthropic, lecture des exports WhatsApp, mémoire de style, rédaction des réponses). `./gradlew :core:test`
- `app/` : l'application Android (Jetpack Compose, service d'accessibilité, écoute des notifications, WorkManager). `./gradlew :app:assembleDebug` (nécessite le SDK Android).
