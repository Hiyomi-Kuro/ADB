---
name: android-ui
description: Operate visible Android UI through Accessibility using semantic selectors before coordinates.
---

# Android UI

Use the current Accessibility node tree as the primary UI state.

Preferred loop:
1. Observe with `ui.dump` when the target is uncertain.
2. Find a semantic target by visible text or accessibility description.
3. Act with `ui.click` or `ui.input_text`.
4. Observe again if the task has another step.

Prefer semantic targets over raw screen coordinates because layouts change across devices, orientation, font scaling, and app versions.

Do not perform irreversible actions merely because a matching button exists. Risk policy still applies after a UI target is found.
