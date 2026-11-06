package com.limelight.utils

/**
 * What a toast says, which picks its colour, word and icon (docs/GUI.md, "Toasts"). Callers pass INFO / SUCCESS /
 * WARNING / ERROR (INFO is the default); [ACTION] is chosen by the shell for a toast that carries an action (Undo).
 */
enum class ToastKind { INFO, SUCCESS, WARNING, ERROR, ACTION }
