(ns reitit-extras.core-test
  (:require [clojure.test :refer :all]
            [reitit-extras.core :as core]))

(deftest test-sum
  (is (= 2 (core/sum 1 1))))
