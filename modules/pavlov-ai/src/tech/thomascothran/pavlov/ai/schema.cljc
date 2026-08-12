(ns tech.thomascothran.pavlov.ai.schema)

(defmulti ->json-schema type)

(defmulti validate
  (fn [schema _value]
    (type schema)))
(defmulti explain
  (fn [schema _value]
    (type schema)))
(defmulti decode
  (fn [schema _value]
    (type schema)))
(defmulti encode
  (fn [schema _value]
    (type schema)))
