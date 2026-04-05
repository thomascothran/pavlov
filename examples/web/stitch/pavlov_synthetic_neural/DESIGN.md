# Design System Specification: Pavlov

## 1. Overview & Creative North Star
**Creative North Star: "The Synthetic Sentinel"**

This design system moves beyond the cliché "neon-on-black" cyberpunk tropes to create a high-end, tactical interface that feels like the Heads-Up Display (HUD) of a sophisticated autonomous machine. It is designed to feel precise, engineered, and slightly predatory.

The system breaks the "template" look through **Intentional Asymmetry**. We move away from centered, balanced layouts in favor of "Instrument Panel" compositions—where data is pushed to the edges, and the center is reserved for high-impact visual "targets." We utilize sharp, zero-radius corners to evoke the feeling of milled steel and carbon fiber. This is not a "soft" interface; it is a rigid, functional framework for the future of canine robotics.

---

## 2. Colors & Surface Architecture

### The Palette
The color strategy utilizes a "Void" base (`#0e0e0e`) punctuated by hyper-saturated "Signal" colors. 

*   **Primary (`#8ff5ff` / Electric Blue):** Used for "System Critical" data and primary interaction paths.
*   **Secondary (`#ff6b98` / Hot Pink):** Used for "Biological/Neural" interface elements and high-energy CTAs.
*   **Tertiary (`#8eff71` / Toxic Green):** Reserved for "Status: Active" indicators and tactical overlays.
*   **Error (`#ff716c`):** Specifically for "System Breach" or hardware malfunction states.

### The "No-Line" Rule
Traditional 1px borders are strictly prohibited for layout sectioning. In a robotic interface, depth is created by thermal layers, not ink lines.
*   **Boundary Definition:** Transition between `surface` (`#0e0e0e`) and `surface-container-low` (`#131313`) to define regions.
*   **Intentional Contrast:** Use `surface-container-highest` (`#262626`) for persistent sidebars or utility panels to create a "bolted-on" modular feel.

### Glass & Gradient Strategy
To avoid a flat, "webby" look:
*   **The HUD Overlay:** Use `surface-variant` (`#262626`) at 40% opacity with a `20px` backdrop-blur for floating modals.
*   **Chromatic Gradients:** Primary CTAs should utilize a linear gradient from `primary` (`#8ff5ff`) to `primary-container` (`#00eefc`) at a 135-degree angle to simulate glowing neon tubes.

---

## 3. Typography: Tactical Communication

The typography system relies on the tension between the technical precision of **Space Grotesk** and the utilitarian readability of **Manrope**.

*   **Display & Headlines (Space Grotesk):** These are your "Readouts." Use `display-lg` (3.5rem) for brand moments. Headers should often be set in all-caps with a `0.05em` letter spacing to mimic serial numbers on hardware.
*   **Body & Titles (Manrope):** This is the "Log Data." It provides a clean, human-readable contrast to the aggressive display type. 
*   **Labels (Space Grotesk):** `label-sm` (`0.6875rem`) is critical for this system. Use it for micro-copy, status bits, and "sensor data" labels. 

**Signature Style:** Occasionally "glitch" headline text by offsetting a duplicate layer of text in `secondary` (`#ff6b98`) at 20% opacity, shifted 2px to the left.

---

## 4. Elevation & Depth: Tonal Layering

In the Pavlov ecosystem, depth is "Physical Stacking," not "Optical Casting."

*   **The Layering Principle:** 
    *   **Base:** `surface` (#0e0e0e)
    *   **Level 1 (Sections):** `surface-container-low` (#131313)
    *   **Level 2 (Cards/Modules):** `surface-container-highest` (#262626)
*   **Ambient Shadows:** Avoid black shadows. Use `primary` (#8ff5ff) at 8% opacity with a `40px` blur for floating elements. This creates a "glow" rather than a shadow, simulating the light emitted from a neon screen.
*   **The "Ghost Border" Fallback:** If a divider is required for complex data density, use `outline-variant` (`#484847`) at **15% opacity**. It should be barely felt, only sensed.

---

## 5. Components & Interface Elements

### Buttons (The "Actuators")
*   **Primary:** Solid `primary` background, `on-primary` text. **Corner radius must be 0px.**
*   **Secondary:** Ghost style. `outline` border at 40% opacity. On hover, the background fills with `secondary_container` (`#bb0058`) and triggers a subtle `1px` jitter animation.
*   **Tertiary:** All-caps `label-md` text with a `tertiary` (`#8eff71`) underline that only extends 40% of the text width.

### Input Fields
*   **Styling:** Background `surface-container-highest`. Bottom-only border using `primary-dim`. 
*   **Focus State:** The bottom border glows (add a `4px` outer glow of `primary`). Helper text should appear in `label-sm` like a terminal prompt (`>_`).

### Cards & Lists
*   **Forbidden:** Horizontal dividers.
*   **Guidance:** Use a `spacing.8` (1.75rem) vertical gap to separate list items. Use a small `2px x 2px` square of `tertiary` in the top-left corner of a card to indicate "System Active."

### Custom Component: The "Data Scrambler"
For loading states or hover reveals, use a "Glitch Overlay"—a brief CSS animation that slices the component into three horizontal segments and shifts them momentarily.

---

## 6. Do’s and Don'ts

### Do:
*   **Do Use Monospaced Vibes:** Align numbers in tables using the `label` scale to maintain the "sensor readout" aesthetic.
*   **Do Embrace the Void:** Leave large areas of `surface` (#0e0e0e) empty. Sophistication in this system comes from what *isn't* lit.
*   **Do Use Metallic Textures:** Apply a very subtle noise texture (3% opacity) over the entire background to simulate brushed metal or a high-iso camera feed.

### Don't:
*   **Don't Round Corners:** Zero pixels, everywhere. No exceptions. Rounded corners break the "industrial robot" metaphor.
*   **Don't Use Pure White for Body Text:** Use `on-surface-variant` (`#adaaaa`) for long-form text to reduce eye strain against the black background. Reserve `ffffff` for headlines only.
*   **Don't Over-Glitch:** Motion should be tactical and rare. If everything glitches, the interface feels broken rather than intentional.