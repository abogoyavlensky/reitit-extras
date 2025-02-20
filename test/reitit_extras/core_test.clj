(ns reitit-extras.core-test
  (:require [clojure.test :refer :all]
            [reitit-extras.core :as core]))

(deftest test-string->16-byte-array
  (testing "converts strings to MD5 byte array"
    ;; Test with empty string
    (let [empty-result (core/string->16-byte-array "")]
      (is (= 16 (count empty-result)))
      (is (= [-44 29 -116 -39 -113 0 -78 4 -23 -128 9 -104 -20 -8 66 126]
             (vec empty-result))))

    ;; Test with "test" string
    (let [test-result (core/string->16-byte-array "test")]
      (is (= 16 (count test-result)))
      (is (= [9 -113 107 -51 70 33 -45 115 -54 -34 78 -125 38 39 -76 -10]
             (vec test-result))))

    ;; Test with longer string
    (let [long-result (core/string->16-byte-array "Hello, World!")]
      (is (= 16 (count long-result)))
      (is (= [101 -88 -30 125 -120 121 40 56 49 -74 100 -67 -117 127 10 -44]
             (vec long-result))))))
