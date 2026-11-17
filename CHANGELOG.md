# Changelog

A new version at the top of this file makes a release when it reaches main.
Pushes without a new version here build and test, but do not update anyone's apps.
The notes under the newest version show once in both apps after they update.

## 1.5.0

- The PC app keeps running when you close its window, so your phone stays connected. Open it again from the Mobile Dock icon in the tray, next to the clock. Right-click the icon for Show pairing PIN and Quit.
- Starting the PC app a second time opens the window of the one already running.
- Start with Windows and updates now start the PC app in the tray, without a window.
- To make the X button quit, turn Keep running in the background off on the Settings tab. Quit Mobile Dock is there too.
- Fix: closing the Search presets editor quickly could stop the PC window from updating.

## 1.4.1

- Search: remove a recent search with the X beside it, or tap Clear all to remove them all.

## 1.4.0

- Search: a new page on the phone, next to Apps. Type once, and Auto sends it to the right place: "yt lofi" searches YouTube, "youtube" opens youtube.com, with Chrome in front it searches in Chrome, and otherwise it searches Google. Or tap a preset to choose.
- Presets come with icons: the site's own icon for web searches, the app's icon for in-app searches, a picture you pick, or a simple line icon.
- Edit presets in the PC window: Shortcuts tab, Search presets. Each preset has a name, a keyword, the apps it belongs to, an action with {q} for your text and a browser. Export setup includes them.
- Your last 10 searches stay on the phone, one tap to repeat.

## 1.3.2

- Security fix on the phone: a file shared from another app can no longer point into Mobile Dock's own storage, where the pairing keys are kept. Such a share is refused before anything is read.

## 1.3.1

- Now playing has -5 and +5 buttons beside the arrows. They move a YouTube video or a song by 5 seconds, even while the browser is in the background or on another monitor. They work in Chrome and Edge, and in any player that lets Windows move its position.
- The dock now controls the player that is actually playing. A paused Spotify no longer takes over the buttons while a YouTube video plays.

## 1.3.0

- Releases now come with notes like these, and only when there is something to ship.
- Settings on the phone has Copy diagnostics, and the PC window has it too. It copies a short error log to paste when you report a problem. Nothing is sent anywhere.
- The PC window can export and import your pinned apps and shortcut pads.
- Better support for screen readers and large text.
- Tap vibration only buzzes on a real tap, not when a scroll starts on a button.

## 1.2.47

- Tap vibration with Off, Light, Medium and Strong.
- Night dimming to a quiet clock after a few idle minutes.
- Volume mixer, clipboard text, Wake PC, temperatures, QR pairing, custom tile actions and volume buttons.
