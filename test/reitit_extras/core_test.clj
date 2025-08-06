(ns reitit-extras.core-test
  (:require [clojure.test :refer :all]
            [reitit-extras.core :as core]
            [reitit.coercion.malli :as coercion-malli]))

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

(deftest test-non-throwing-coerce-request-middleware
  (testing "middleware compilation"
    (testing "returns nil when no coercion"
      (let [middleware-config {:coercion nil
                               :parameters {:query [:map [:id :int]]}}
            compiled ((:compile core/non-throwing-coerce-request-middleware) middleware-config {})]
        (is (nil? compiled))))

    (testing "returns empty map when no parameters"
      (let [middleware-config {:coercion coercion-malli/coercion
                               :parameters nil}
            compiled ((:compile core/non-throwing-coerce-request-middleware) middleware-config {})]
        (is (= {} compiled))))

    (testing "returns middleware function when coercion and parameters present"
      (let [middleware-config {:coercion coercion-malli/coercion
                               :parameters {:query [:map [:id :int]]}}
            compiled ((:compile core/non-throwing-coerce-request-middleware) middleware-config {})]
        (is (fn? compiled)))))

  (testing "successful coercion"
    (let [middleware-config {:coercion coercion-malli/coercion
                             :parameters {:query [:map [:id :int]]
                                          :path [:map [:user-id :int]]}}
          middleware-fn ((:compile core/non-throwing-coerce-request-middleware) middleware-config {})
          handler (fn [request] {:status 200
                                 :body (:parameters request)})
          wrapped-handler (middleware-fn handler)]

      (testing "processes query parameters"
        (let [request {:query-params {"id" "123"}}
              response (wrapped-handler request)]
          (is (= 200 (:status response)))
          (is (contains? (:body response) :query))
          (is (contains? (get-in response [:body :query]) :id))))

      (testing "processes path parameters"
        (let [request {:path-params {"user-id" "456"}}
              response (wrapped-handler request)]
          (is (= 200 (:status response)))
          (is (contains? (:body response) :path))
          (is (contains? (get-in response [:body :path]) :user-id))))

      (testing "processes multiple parameter types"
        (let [request {:query-params {"id" "123"}
                       :path-params {"user-id" "456"}}
              response (wrapped-handler request)]
          (is (= 200 (:status response)))
          (is (contains? (:body response) :query))
          (is (contains? (:body response) :path))))))

  (testing "validation errors without throwing"
    (let [middleware-config {:coercion coercion-malli/coercion
                             :parameters {:query [:map [:id :int]]}}
          middleware-fn ((:compile core/non-throwing-coerce-request-middleware) middleware-config {})
          handler (fn [request] {:status 200
                                 :body {:errors (:errors request)
                                        :parameters (:parameters request)}})
          wrapped-handler (middleware-fn handler)]

      (testing "adds errors for invalid query parameters"
        (let [request {:query-params {"id" "invalid"}}
              response (wrapped-handler request)]
          (is (= 200 (:status response)))
          (is (contains? (:body response) :errors))
          (is (not (nil? (get (:body response) :errors))))))

      (testing "preserves raw parameters when validation fails"
        (let [request {:query-params {"id" "invalid"}
                       :form-params {"name" "test"}
                       :path-params {"user-id" "789"}}
              response (wrapped-handler request)]
          (is (= 200 (:status response)))
          (is (contains? (:body response) :errors))
          (is (= {:query {:id "invalid"}
                  :form {:name "test"}
                  :path {:user-id "789"}}
                 (get (:body response) :parameters)))))))

  (testing "form parameters handling"
    (let [middleware-config {:coercion coercion-malli/coercion
                             :parameters {:form [:map [:age :int]]}}
          middleware-fn ((:compile core/non-throwing-coerce-request-middleware) middleware-config {})
          handler (fn [request] {:status 200
                                 :body (:parameters request)})
          wrapped-handler (middleware-fn handler)]

      (testing "coerces form parameters"
        (let [request {:form-params {"age" "25"}}
              response (wrapped-handler request)]
          (is (= 200 (:status response)))
          (is (contains? (:body response) :form))
          (is (contains? (get-in response [:body :form]) :age))))

      (testing "handles form parameter validation errors"
        (let [request {:form-params {"age" "invalid"}}
              handler-with-errors (fn [request] {:status 200
                                                 :errors (:errors request)})
              response ((middleware-fn handler-with-errors) request)]
          (is (= 200 (:status response)))
          (is (contains? response :errors))))))

  (testing "async handler support"
    (let [middleware-config {:coercion coercion-malli/coercion
                             :parameters {:query [:map [:id :int]]}}
          middleware-fn ((:compile core/non-throwing-coerce-request-middleware) middleware-config {})
          async-handler (fn [request respond _raise]
                          (respond {:status 200
                                    :body (:parameters request)}))
          wrapped-handler (middleware-fn async-handler)]

      (testing "works with async handlers"
        (let [request {:query-params {"id" "123"}}
              result (atom nil)
              respond-fn #(reset! result %)
              raise-fn #(throw %)]
          (wrapped-handler request respond-fn raise-fn)
          (is (contains? (get @result :body) :query))))

      (testing "handles async validation errors"
        (let [request {:query-params {"id" "invalid"}}
              result (atom nil)
              respond-fn #(reset! result %)
              raise-fn #(throw %)
              handler (fn [request respond _raise]
                        (respond {:status 200
                                  :errors (:errors request)}))
              wrapped-error-handler (middleware-fn handler)]
          (wrapped-error-handler request respond-fn raise-fn)
          (is (contains? @result :errors))))))

  (testing "edge cases"
    (let [middleware-config {:coercion coercion-malli/coercion
                             :parameters {:query [:map [:id :int]]}}
          middleware-fn ((:compile core/non-throwing-coerce-request-middleware) middleware-config {})
          handler (fn [request] {:status 200
                                 :parameters (:parameters request)
                                 :errors (:errors request)})
          wrapped-handler (middleware-fn handler)]

      (testing "handles empty parameters"
        (let [request {}
              response (wrapped-handler request)]
          (is (= 200 (:status response)))
          (is (contains? response :errors))))

      (testing "handles nil parameter values"
        (let [request {:query-params {"id" nil}}
              response (wrapped-handler request)]
          (is (= 200 (:status response)))
          (is (contains? response :errors)))))))
