(ns pavlov-web-example.game-of-life.page
  (:require [dev.onionpancakes.chassis.core :as chassis]))

(def ^:private tailwind-config-script
  "tailwind.config = {\n  darkMode: \"class\",\n  theme: {\n    extend: {\n      colors: {\n        background: \"#0e0e0e\",\n        primary: \"#8ff5ff\",\n        secondary: \"#ff6b98\",\n        tertiary: \"#8eff71\",\n        surface: \"#0e0e0e\",\n        \"surface-container-low\": \"#131313\",\n        \"surface-container-highest\": \"#262626\",\n        \"on-surface\": \"#ffffff\",\n        \"on-surface-variant\": \"#adaaaa\",\n        outline: \"#767575\",\n        \"outline-variant\": \"#484847\"\n      },\n      fontFamily: {\n        headline: [\"Space Grotesk\"],\n        body: [\"Manrope\"],\n        label: [\"Space Grotesk\"]\n      },\n      borderRadius: {\n        DEFAULT: \"0rem\",\n        lg: \"0rem\",\n        xl: \"0rem\",\n        full: \"9999px\"\n      }\n    }\n  }\n}")

(def ^:private style-text
  "body {\n  margin: 0;\n  background-image: radial-gradient(circle at 50% 50%, rgba(38, 38, 38, 0.3) 0%, rgba(14, 14, 14, 1) 100%);\n  cursor: crosshair;\n}\n\n.scanline {\n  width: 100%;\n  height: 100px;\n  z-index: 10;\n  background: linear-gradient(0deg, rgba(143, 245, 255, 0) 0%, rgba(143, 245, 255, 0.05) 50%, rgba(143, 245, 255, 0) 100%);\n  position: absolute;\n  pointer-events: none;\n}\n\n.game-of-life-status {\n  background: #131313;\n  padding: 0.75rem 1rem;\n  font-family: \"Space Grotesk\", sans-serif;\n  font-size: 0.75rem;\n  letter-spacing: 0.2em;\n  text-transform: uppercase;\n}\n\n.game-of-life-status--running {\n  color: #8eff71;\n  box-shadow: inset 3px 0 0 #8eff71;\n}\n\n.game-of-life-status--paused {\n  color: #adaaaa;\n  box-shadow: inset 3px 0 0 rgba(118, 117, 117, 0.5);\n}\n\n[data-game-of-life-board] {\n  display: grid;\n  gap: 0.5rem;\n  padding: 0.75rem;\n  background: #0e0e0e;\n}\n\n.game-of-life-cell {\n  width: 4.5rem;\n  height: 4.5rem;\n  border: 0;\n  font-family: \"Space Grotesk\", sans-serif;\n  font-size: 1.5rem;\n  font-weight: 700;\n  cursor: pointer;\n  transition: background-color 150ms ease, box-shadow 150ms ease, color 150ms ease;\n}\n\n.game-of-life-cell--dead {\n  background: #131313;\n  color: #767575;\n  box-shadow: inset 0 0 0 1px rgba(72, 72, 71, 0.35);\n}\n\n.game-of-life-cell--alive {\n  background: #8ff5ff;\n  color: #0e0e0e;\n  box-shadow: 0 0 18px rgba(143, 245, 255, 0.45), inset 0 0 0 1px rgba(255, 107, 152, 0.45);\n}")

(defn- board-cells
  [height width]
  (for [row (range height)
        col (range width)]
    [:button {:class "game-of-life-cell game-of-life-cell--dead"
              :data-cell-state "dead"
              :data-game-of-life-cell true
              :data-col (str col)
              :data-row (str row)
              :pavlov-col (str col)
              :pavlov-on-click ":game-of-life/cell-clicked"
              :pavlov-row (str row)
              :type "button"}
     "."]))

(defn- shell
  [{:keys [height width]}]
  [:html {:class "dark" :lang "en"}
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:content "width=device-width, initial-scale=1.0" :name "viewport"}]
    [:title "PAVLOV_OS // GAME_OF_LIFE"]
    [:script {:src "https://cdn.tailwindcss.com?plugins=forms,container-queries"}]
    [:link {:href "https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@300;400;500;600;700;900&family=Manrope:wght@300;400;500;600;700;800&display=swap"
            :rel "stylesheet"}]
    [:script {:id "tailwind-config"} tailwind-config-script]
    [:style style-text]]
   [:body {:class "bg-background text-on-surface font-body overflow-x-hidden selection:bg-secondary selection:text-black"
           :data-pavlov-page "game-of-life"}
    [:header {:class "fixed top-0 z-50 flex h-16 w-full items-center justify-between bg-[#0e0e0e] px-6 shadow-[0_0_40px_rgba(143,245,255,0.08)]"}
     [:div {:class "flex items-center gap-8"}
      [:span {:class "font-headline text-xl font-black tracking-tighter text-[#8ff5ff]"} "PAVLOV_OS"]
      [:nav {:class "hidden gap-6 md:flex"}
       [:span {:class "border-b-2 border-[#8ff5ff] pb-1 font-['Space_Grotesk'] text-xs uppercase tracking-[0.05em] text-[#8ff5ff]"} "GAME_OF_LIFE"]
       [:span {:class "font-['Space_Grotesk'] text-xs uppercase tracking-[0.05em] text-[#adaaaa]"} "SYNTHETIC_GRID"]]]
     [:div {:class "flex items-center gap-2 font-['Space_Grotesk'] text-[10px] uppercase tracking-[0.2em] text-[#8eff71]"}
      [:div {:class "h-1.5 w-1.5 bg-[#8eff71]"}]
      [:span "SYSTEM_ACTIVE"]]]
    [:main {:class "flex min-h-screen w-full flex-col pt-16 md:flex-row"
            :id "game-of-life-root"}
     [:section {:class "relative flex flex-1 flex-col items-center justify-center overflow-hidden bg-surface p-8"}
      [:div {:class "mb-4 flex w-full max-w-4xl items-end justify-between font-headline text-[10px] uppercase tracking-widest"}
       [:div {:class "flex flex-col text-on-surface-variant"}
        [:span "NODE_STATUS: " [:span {:class "text-tertiary"} "STABLE"]]
        [:span "ARRAY_DIM: " [:span {:class "text-primary"} (str height "x" width "_SHARED")]]]
       [:h1 {:class "text-2xl font-black tracking-tighter text-white"} "GAME_OF_LIFE"]
       [:div {:class "flex flex-col text-right text-on-surface-variant"}
        [:span "MODE: " [:span {:class "text-secondary"} "BACKEND_DRIVEN"]]
        [:span "SYNC: " [:span {:class "text-primary"} "WEBSOCKET"]]]]
      [:div {:class "relative border border-outline-variant bg-surface-container-highest p-1 shadow-[0_0_50px_rgba(143,245,255,0.05)]"}
       [:div {:class "scanline"}]
       [:div {:class "bg-background p-4"}
        (into
         [:section {:aria-label "board"
                    :data-game-of-life-board true
                    :style (str "grid-template-columns: repeat(" width ", 4.5rem);")}]
         (board-cells height width))]]]
     [:aside {:class "z-20 flex w-full flex-col gap-8 bg-surface-container-highest p-6 md:w-80"}
      [:div {:class "space-y-4"}
       [:div {:class "mb-2 flex items-center gap-2"}
        [:div {:class "h-1.5 w-1.5 bg-tertiary"}]
        [:h2 {:class "font-headline text-xs font-bold uppercase tracking-[0.2em] text-on-surface-variant"} "CONTROL_PANEL"]]
       [:p {:class "game-of-life-status game-of-life-status--paused"
            :data-game-of-life-status true}
        "Waiting for backend"]
       [:section {:aria-label "controls"
                  :class "flex flex-col gap-3 pt-2"}
        [:button {:class "bg-primary px-4 py-4 font-headline text-sm font-black tracking-tighter text-black transition-all hover:bg-[#00eefc]"
                  :id "game-of-life-start-button"
                  :pavlov-on-click ":game-of-life/start-clicked"
                  :type "button"}
         "START_SYNC"]
        [:div {:class "grid grid-cols-2 gap-3"}
         [:button {:class "border border-outline-variant px-4 py-3 font-headline text-[10px] font-bold uppercase tracking-widest text-on-surface transition-all hover:bg-surface-container-low"
                   :id "game-of-life-pause-button"
                   :pavlov-on-click ":game-of-life/pause-clicked"
                   :type "button"}
          "PAUSE"]
         [:button {:class "border border-outline-variant px-4 py-3 font-headline text-[10px] font-bold uppercase tracking-widest text-on-surface transition-all hover:bg-surface-container-low"
                   :id "game-of-life-reset-button"
                   :pavlov-on-click ":game-of-life/reset-clicked"
                   :type "button"}
          "RESET"]]]]
      [:div {:class "mt-auto border-t border-outline-variant pt-6"}
       [:label {:class "mb-2 block font-headline text-[10px] uppercase tracking-widest text-on-surface-variant"}
        "DIAGNOSTIC_LOG"]
       [:div {:class "space-y-1 font-headline text-[9px] text-on-surface-variant"}
        [:p [:span {:class "text-tertiary"} "INFO:"] " BACKEND STATE OWNS GENERATIONS."]
        [:p [:span {:class "text-tertiary"} "INFO:"] " CELL CLICKS FORWARD OVER PAVLOV EVENTS."]
        [:p [:span {:class "text-secondary"} "WARN:"] " DOM CLASSES ARE SERVER-MANAGED."]]]]]
    [:script {:src "/js/main.js?v=snazzy-demo-1"}]]])

(defn render-page
  [{:keys [height width]}]
  (str "<!DOCTYPE html>\n\n"
       (chassis/html (shell {:height height
                             :width width}))))
