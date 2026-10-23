# Delos GUI – vision

The starting point for the new Delos UI (Phase 6). Written by the project owner; layout ideas may borrow from PS5, SteamOS and Switch, **the visual design must be original**.

## Experience

- The UI is one continuous **experience from the very first launch**, not a set of separate screens.
- **Console-like layout**; modern and minimalist, with **lots of animations and effects** and especially **polished focus animations** everywhere (menus and anywhere else).
- **Several types of dynamic backgrounds.**
- **Light / dark mode**, plus an extra **Material You** switch that takes the overall color palette from the system. With Material You on, **all dynamic backgrounds use only the system palette** (no other colors mixed in); the "System" wallpaper color is the default.
- Overlays such as the **portal and the game loader blur the content behind them**.
- Slightly **rounded** design with generous paddings and margins.
- An **interface scale** setting enlarges text, the status bar and the button hints (the covers keep their size).
- Heavy use of **icons** from a modern icon font with as many free icons as possible – **not Font Awesome**.

## First launch

1. The user is asked to **create a profile**: a splash screen with **circular icons** (similar in spirit to the PS5 UI).
2. In the same style, the next step automatically shows the **PCs to pair, in circles**.
3. Logging in to a PC plays the main **"portal" animation** (not the same as the PlayStation Portal one). The same kind of animation is used when connecting to a remote device elsewhere in the UI.

## Home

- After connecting to a PC, the main layout shows the **apps / games with the correct aspect ratio for SteamGridDB covers** (portrait).
- Games are laid out **side by side in portrait covers**; the **first game is wide** (landscape SteamGridDB cover). Scrolling is **vertical**.
- A second home layout, switchable in the settings: **one horizontally scrolling row** with everything the grid has (logo, hero background, info, wide first tile), placed a bit lower so more of the game background is visible; the focused game stays near the left edge and the row fades out at the edges.
- **Top center bar** with icons for **settings, profile and devices** (e.g. renaming a PC on the client side). On the **right**, separately: **battery, Wi-Fi** and other status (Delos draws its own status bar – the system bars are hidden).

## Launching a game

- Starting a game shows an **animated circular loader**, then the UI **"dives" into the streamed game**.

## SteamGridDB

- **Built-in SteamGridDB integration**: the user enters their own API key and images are downloaded on the client side.
- For every game Delos downloads **four images**: the **portrait cover**, the **wide cover**, a **large background (hero)** and the **logo**.
- Must also run **automatically for all games at once**.
- When a game is focused on the home screen, its **logo is shown in the top area** (the covers sit a bit lower) and the **background changes to the game's hero image** with a fade in/out and a **blur effect**. **Blur amount and opacity are configurable.** The hero image **blends with the dynamic background** – the animated effects (aurora, waves, silk, contour lines…) light it up instead of covering it.
- Open question: can this be synchronized with Vibepollo?

## Menus and settings

- Menus and settings follow a **PS5-like layout**, but the main settings screen also shows the **main categories as icon tabs** that can be switched.

## Controls

- Primary input: **D-pad or left stick + A, B, X, Y**.
- The **two menu buttons at the top left and top right** of the handheld are used in the UI: one opens a **context menu**, the other a **side menu**.
- Navigation must be **intuitive and direct**: pressing a direction takes you where you want to go, ideally in **one press** – never left, down, down just to reach something.
