# Changelog

A new version at the top of this file makes a release when it reaches main.
Pushes without a new version here build and test, but do not update anyone's apps.
The notes under the newest version show once in both apps after they update.

## 2.5.1

- Playing: a volume slider and a mute button for the whole PC sit next to shuffle and repeat. They move the same level as the volume in the Windows taskbar. Update the PC app too, or the slider stays hidden.

## 2.5.0

- Easier to read: blue links, red buttons, orange text and gray hints are darker in light mode and lighter in dark mode. Every text color now reaches the WCAG AA contrast level on its background, in both apps.
- Clearer names on the phone: Turn on your PC sends the wake signal to the PC. Open when your PC starts wakes the phone when the PC starts. The second one uses the same name in Settings, on the connect page and in its status.
- Tablets: app tiles grow to fill the screen, and Settings shows its list on the left with the open page on the right.
- Playing: the three volume buttons are gone. Volume stays on Controls and on the phone's own volume buttons.
- PC, Settings: Appearance is one segmented control. Private repository token moved to Backup and help.

## 2.4.5

- Phone: Start with your PC has a switch at the top of Settings. Turn it off and the phone no longer wakes when the PC starts. Before, the switch was only on the connect page, which you never see while connected.

## 2.4.4

- PC: every page scrolls when the window is too short, also with Windows display scaling. Before, the bottom of Settings was cut off with no scrollbar.
- PC: the mouse wheel scrolls the page wherever the pointer is on it, also over cards and text.

## 2.4.3

- PC, Apps: each pinned app is its own row with up and down arrows and a red Unpin. No more select first, then move.
- PC, Phone: the pairing steps sit above the PIN, where you look. Remove is red, and still asks before it removes a phone.
- PC, Phone: in a narrow window the QR code moves under the buttons instead of covering them.
- Phone: Touch and Stats show their page name at the top, like the other pages. Fan speeds sit beside the Stats title.
- Phone: Busiest apps shows only the rows that fit, so the last one is never cut in half.
- Phone: Capture labels wrap to two lines on small phones instead of getting cut.

## 2.4.2

- New icons for Touch (a touchpad) and Shortcuts (the Command key), so no two icons in the column look alike.
- Shortcuts: one button at the top picks the pad. Its list starts with Follow the app in front. The separate target button is gone.
- Touch: pointer speed moved from the mouse buttons to the touchpad's top corner, as Slow, Normal and Fast.
- Sliders in light mode have a gray rim, so you can see the knob on a white card.

## 2.4.1

- Labels under app icons in Settings, Display works again. Turn it off and Apps shows only the icons, a little bigger.

## 2.4.0

- On phones, the sidebar is a slim column of icons now. Pages get about 140 dp more room, so labels such as "Picture to clipboard" and the app names in Stats show in full. Long press an icon to see its name.
- Fullscreen sits at the top of the icons, in gray. Settings sits at the bottom, with a dot while the PC is not connected.
- Your PC's name and connection show at the top of Settings.
- Tablets keep the sidebar with names.
- The connect page shows the PCs it finds as a list, with Paired or New. While it searches, gray rows stand in for them.
- When it finds no PC, it shows three steps to fix that, and Wake your PC if the phone knows your PC.
- Enter address, USB cable, Start with your PC and Forget paired PCs moved into a list on the right. Each opens in place, with Other ways at the top to go back.
- With large text, app names under the icons no longer get cut off.

## 2.3.0

- The idle screen's side tiles are a checklist now: Clock, Date, Calendar, Playing and Battery. Check up to 4.
- Arrows on a checked tile move it up or down, so you set the order.
- One or two side tiles sit one above the other. With three, the first is wide on top and the other two share the row below. Four share a 2 x 2 grid.
- Your tiles from 2.2.0 stay checked.
- Changing the idle screen no longer jumps the Settings page back to the top.

## 2.2.0

- Settings is a short list now: Display, Idle screen, Buttons and feedback, and Updates. Each opens its own page. Back or Settings at the top returns to the list.
- The idle screen takes a second side tile. The two sit one above the other, for example the song and the battery.
- The side tiles can show the clock too, so a big calendar or song can have the time next to it.
- Pick each tile from a list in Settings, Idle screen, Tiles.

## 2.1.0

- A new idle screen, like a bedside clock: big bold digits in a color you pick, on black.
- Pick the tiles in Settings, Idle screen. The big tile shows the clock, this month's calendar or the song that is playing. The side tile shows the date, the calendar, the song, the phone's battery, or nothing.
- Six colors: Blue, White, Red, Orange, Green and Pink. Red is the easiest on the eyes at night.
- Preview in Settings shows it right away.

## 2.0.0

- A new look on the phone and the PC, following Apple's design guidelines: grouped lists, switches, capsule buttons and the Inter typeface.
- Light and dark. Automatic follows your phone's and Windows' setting. Change it under Settings, Appearance.
- The phone has a floating sidebar instead of the dock bar. Settings is the gear at the top, next to your PC's name. Capture now opens from Controls.
- Now Playing looks like Apple Music: the cover on the left, and its colors fill the screen.
- The PC window has a sidebar, rounded cards and switches.

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
