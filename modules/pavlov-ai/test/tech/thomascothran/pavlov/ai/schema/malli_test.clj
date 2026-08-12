(ns tech.thomascothran.pavlov.ai.schema.malli-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.ai.schema :as schema]
            ;; Requiring this namespace installs the Malli defmethods.
            [tech.thomascothran.pavlov.ai.schema.malli]))

(deftest vector-malli-schema->json-schema
  (testing "Malli vector schemas can be rendered as JSON Schema"
    (let [json-schema (schema/->json-schema
                       [:map
                        [:lookback :string]])]
      (is (= "object" (:type json-schema)))
      (is (= "string" (get-in json-schema [:properties :lookback :type])))
      (is (= [:lookback] (:required json-schema))))))

(deftest vector-malli-schema-validation
  (testing "valid values pass validation and invalid values produce explain data"
    (let [email-list-request [:map
                              [:lookback :string]]]
      (is (true? (schema/validate email-list-request
                                  {:lookback "24 hours"})))
      (is (false? (schema/validate email-list-request
                                   {:lookback 24})))
      (is (nil? (schema/explain email-list-request
                                {:lookback "24 hours"})))
      (is (= [{:path [:lookback]
               :in [:lookback]
               :schema :string
               :value 24}]
             (:errors (schema/explain email-list-request
                                      {:lookback 24})))))))

(deftest vector-malli-schema-json-transformations
  (testing "JSON-compatible values are decoded to Clojure values"
    (is (= {:labels #{:boss :urgent}}
           (schema/decode [:map
                           [:labels [:set :keyword]]]
                          {:labels ["urgent" "boss"]}))))

  (testing "Clojure values are encoded to JSON-compatible values"
    (is (= {:labels #{"boss" "urgent"}}
           (schema/encode [:map
                           [:labels [:set :keyword]]]
                          {:labels #{:boss :urgent}})))))
