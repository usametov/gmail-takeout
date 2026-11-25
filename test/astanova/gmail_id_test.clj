(ns astanova.gmail-id-test
  (:require [astanova.gmail-id :as gid]
            [clojure.test :refer [deftest is testing]]))

(deftest test-api-hex-to-canonical
  (testing "real Gmail API IDs convert to the decimal Takeout form"
    (is (= "1869976353534821376" (gid/api-hex->canonical "19f37d9602d2c400")))
    (is (= "1869976293963969653" (gid/api-hex->canonical "19f37d88241fb475")))
    (is (= "19f37d9602d2c400" (gid/canonical->api-hex "1869976353534821376")))))

(deftest test-canonical-validation
  (testing "only positive unsigned 64-bit decimal IDs are canonical"
    (is (gid/canonical? "1869976353534821376"))
    (is (not (gid/canonical? "0")))
    (is (not (gid/canonical? "01869976353534821376")))
    (is (not (gid/canonical? "18446744073709551616")))
    (is (gid/canonical? "18446744073709551615"))
    (is (not (gid/canonical? "19f37d9602d2c400")))
    (is (not (gid/canonical? nil)))))

(deftest test-api-hex-validation
  (testing "API hex must fit in 64 bits"
    (is (gid/api-hex? "19f37d9602d2c400"))
    (is (gid/api-hex? "ffffffffffffffff"))
    (is (not (gid/api-hex? "10000000000000000")))
    (is (not (gid/api-hex? "not-hex")))
    (is (not (gid/api-hex? "0")))))

(deftest test-canonicalize-encoding
  (testing "context selects the base; ambiguous all-digit strings stay decimal"
    (is (= "1869976353534821376"
           (gid/canonicalize "19f37d9602d2c400" :gws-api)))
    (is (= "1869976353534821376"
           (gid/canonicalize "1869976353534821376" :canonical)))
    (is (= "1869976353534821376"
           (gid/canonicalize "1869976353534821376")))
    (is (nil? (gid/canonicalize "gmail-1")))
    (is (nil? (gid/canonicalize "thread-1")))
    (is (nil? (gid/canonicalize "19f37d9602d2c400" :canonical)))))

(deftest test-gmail-iris
  (testing "message/thread IRIs use canonical decimal IDs"
    (is (= "gmail:message/1869976353534821376"
           (gid/message-iri "19f37d9602d2c400")))
    (is (= "gmail:thread/1869976293963969653"
           (gid/thread-iri "19f37d88241fb475")))
    (is (nil? (gid/message-iri "gmail-1")))))
