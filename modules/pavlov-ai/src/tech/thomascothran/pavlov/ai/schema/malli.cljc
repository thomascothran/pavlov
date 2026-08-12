(ns tech.thomascothran.pavlov.ai.schema.malli
  (:require [tech.thomascothran.pavlov.ai.schema :as ai-schema]
            [malli.json-schema :as json-schema]
            [malli.core :as m]
            [malli.util :as mu]
            [malli.transform :as mt]))

(def ^:private vector-schema-type
  #?(:clj clojure.lang.IPersistentVector
     :cljs cljs.core/PersistentVector))

(def ^:private json-transformer
  (mt/json-transformer))

(defmethod ai-schema/->json-schema
  vector-schema-type
  [schema]
  (json-schema/transform schema))

(defmethod ai-schema/encode
  vector-schema-type
  [schema x]
  (m/encode schema x json-transformer))

(defmethod ai-schema/decode
  vector-schema-type
  [schema x]
  (m/decode schema x json-transformer))

(defmethod ai-schema/validate
  vector-schema-type
  [schema x]
  (m/validate schema x))

(defmethod ai-schema/explain
  vector-schema-type
  [schema x]
  (mu/explain-data schema x))
