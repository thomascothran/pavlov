(ns tech.thomascothran.pavlov.bprogram.proto)

(defprotocol ListenerManager
  :extend-via-metadata true
  (pop-listeners! [this event-filter]
    "The listening bthreads. Removes the bthreads as listeners")
  (listen! [this event-filter bthread]
    "Register a bthread to listen for an event"))
