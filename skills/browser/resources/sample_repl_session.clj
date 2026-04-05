(require '[etaoin.api :as e]
         '[etaoin.keys :as k]
         '[clojure.string :as str])

;; Start WebDriver for Chrome in headless mode
(comment
  *e
  (e/with-chrome-headless driver
    (e/driver-type driver)

    ;; let's perform a quick Wiki session

    ;; navigate to Wikipedia
    (e/go driver "https://en.wikipedia.org/")

    ;; make sure we aren't using a large screen layout
    (e/set-window-size driver {:width 1280 :height 800})

    ;; wait for the search input to load
    (e/wait-visible driver [{:tag :input :name :search}])

    ;; search for something interesting
    (e/fill driver {:tag :input :name :search} "Clojure programming language")
    (e/wait driver 1)
    (e/fill driver {:tag :input :name :search} k/enter)
    (e/wait-visible driver {:class :mw-search-results})

    ;; click on first match
    (e/click driver [{:class :mw-search-results} {:class :mw-search-result-heading} {:tag :a}])
    (e/wait-visible driver {:id :firstHeading})

    ;; check our new url location
    ;; (wikipedia can tack on a querystring, for result consistency we'll ignore it)
    (-> (e/get-url driver) (str/split #"\?") first)
    ;; => "https://en.wikipedia.org/wiki/Clojure"

    ;; and our new title
    (e/get-title driver)
    ;; => "Clojure - Wikipedia"

    ;; does the page have Clojure in it?
    (e/has-text? driver "Clojure")
    ;; => true

    ;; navigate through history
    (e/back driver)
    (e/forward driver)
    (e/refresh driver)
    (e/get-title driver)
    ;; => "Clojure - Wikipedia"

    ;; let's explore the info box
    ;; What's its caption? Let's select it with a css query:
    (e/get-element-text driver {:css "table.infobox caption"})
    ;; => "Clojure"

    ;; Ok, now let's try something trickier
    ;; Maybe we are interested in what value the infobox holds for the Family row:
    (let [wikitable (e/query driver {:css "table.infobox.vevent tbody"})
          row-els (e/query-all-from driver wikitable {:tag :tr})]
      (for [row row-els
            :let [header-col-text (e/with-http-error
                                    (e/get-element-text-el
                                     driver
                                     (e/query-from driver row {:tag :th})))]
            :when (= "Family" header-col-text)]
        (e/get-element-text-el driver (e/query-from driver row {:tag :td}))))
    ;; => ("Lisp")

    ;; Etaoin gives you many options; we can do the same-ish in one swoop in XPath:
    (e/get-element-text driver "//table[@class='infobox vevent']/tbody/tr/th[text()='Family']/../td")
    ;; => "Lisp"
    ))
