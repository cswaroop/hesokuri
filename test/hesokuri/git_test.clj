; Copyright (C) 2013 Google Inc.
;
; Licensed under the Apache License, Version 2.0 (the "License");
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;    http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.

(ns hesokuri.git-test
  (:require clojure.java.io
            [hesokuri.transact :as transact])
  (:use [clojure.string :only [trim]]
        clojure.test
        hesokuri.git
        hesokuri.testing.temp)
  (:import [clojure.lang ExceptionInfo]
           [hesokuri.git ArrayBackedHash]))

(def pretend-hash "aaaaaaaaaabbbbbbbbbbccccccccccffffffffff")

(defn cycle-bytes [b count] (byte-array (take count (cycle b))))
(defn cycle-bytes-hash [b] (new ArrayBackedHash (cycle-bytes b 20)))

(deftest test-hex-char-val
  (are [ch exp] (= exp (hex-char-val ch))
       \space nil
       0 nil
       \0 0
       \5 5
       \9 9
       \a 10
       \f 15))

(deftest test-full-hash
  (are [hash exp] (is (= exp (full-hash? hash)))
       "a00000000000000000000000000000000000000b" true
       "a00000000000000000000000000000000000000" false
       "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" false
       "                                        " false
       "1234123412341234123412341234123412341234" true
       pretend-hash true))

(deftest test-ArrayBackedHash-toString
  (is (= "a00000000000000000000000000000000000000b"
         (-> (concat [0xa0] (repeat 18 0) [0x0b])
             byte-array
             ArrayBackedHash.
             .toString))))

(deftest test-ArrayBackedHash-hashCode
  (is (= 0xabcdabcd
         (-> (concat [0xab 0xcd 0xab 0xcd] (repeat 16 0xff))
             byte-array
             ArrayBackedHash.
             .hashCode
             (bit-and (long 0xffffffff))))))

(deftest test-ArrayBackedHash-equals
  (are [cyc1 cyc2 expect]
      (= expect (= (cycle-bytes-hash cyc1) (cycle-bytes-hash cyc2)))
    [0xa0 0xb0] [0xa0 0xb0] true
    [0x88 0x77] [0x88 0x77 0x88] false
    [0x88 0x77 0x88 0x77] [0x88 0x77 0x88 0x77 0x66] false))

(defn byte-stream [bytes]
  (new java.io.ByteArrayInputStream (byte-array bytes)))

(deftest test-read-hash
  (are [source-cycle source-count]
      (let [source-bytes (cycle-bytes source-cycle source-count)
            source-stream (byte-stream source-bytes)
            next-byte (or (get source-bytes 20) -1)
            do-read #(read-hash source-stream)

            expected-hash
            #(ArrayBackedHash.
              (byte-array (take 20 (concat source-bytes (repeat 0)))))]
        (if (< source-count 20)
          (is (thrown? ExceptionInfo (do-read)))
          (is (= (expected-hash) (do-read))))
        ;; Make sure read-hash does not read more bytes than needed.
        (is (= next-byte (.read source-stream))))
    [0] 0
    [1] 20
    [2 3 4] 21
    [5 6 7] 19
    [8 9] 10))

(deftest test-String-Hash-write-binary-hash
  (let [chars (cycle [\a \0 \5 \f \b \9 \2 \8 \7])
        parsed-bytes (cycle [0xa0 0x5f 0xb9 0x28 0x7a 0x05 0xfb 0x92 0x87])]
    (are [length]
      (let [expected (map unchecked-byte (take (quot length 2) parsed-bytes))
            hash-str (apply str (take length chars))
            baos (new java.io.ByteArrayOutputStream)
            actual (do (write-binary-hash hash-str baos)
                       (into [] (.toByteArray baos)))]
        (= expected actual))
      0
      1
      10
      21
      30
      35
      40
      41)))

(deftest read-blob-error
  (with-temp-repo [git-dir]
    (is (full-hash? pretend-hash))
    (is (thrown? ExceptionInfo (read-blob "git" git-dir pretend-hash)))))

(deftest read-blob-success
  (with-temp-repo [git-dir]
    (let [[stdin stdout finish]
          (invoke-streams "git"
                          (args git-dir ["hash-object" "-w" "--stdin"]))
          hash (do (spit stdin "hello Git")
                   (clojure.string/trim (slurp stdout)))]
      (is (= {:exit 0 :err ""} @finish))
      (is (= "hello Git" (read-blob "git" git-dir hash))))))

(deftest test-write-blob-success
  (with-temp-repo [git-dir]
    (let [blob-hash (write-blob "git" git-dir (.getBytes "asdf" "UTF-8"))]
      (is (= "5e40c0877058c504203932e5136051cf3cd3519b" blob-hash))
      (is (= "asdf" (read-blob "git" git-dir blob-hash))))))

(deftest test-write-blob-failure
  (try
    (write-blob "git" (create-temp-dir) (.getBytes "asdf" "UTF-8"))
    (throw (ex-info "should have thrown" {}))
    (catch clojure.lang.ExceptionInfo e
      (is (not= -1 (.indexOf (.getMessage e) "hash-object -w --stdin")))
      (is (not= -1 (.indexOf ((ex-data e) :err) "Not a git repository"))))))

(defn tree-entry-bytes [entry-type filename hash-cycle-bytes]
  (concat (.getBytes (str entry-type " " filename "\u0000") "UTF-8")
          (cycle-bytes hash-cycle-bytes 20)))

(deftest test-read-tree-entry
  (let [entry-bytes (tree-entry-bytes "12345" "filename" [0x01 0x02])]
    (loop [source-len 0]
      (let [do-read
            #(read-tree-entry (byte-stream (take source-len entry-bytes)))]
        (cond
         (zero? source-len) (is (nil? (do-read)))

         (< source-len (count entry-bytes))
         (do (is (thrown? ExceptionInfo (do-read))
                 (str "Read only first " source-len " bytes of entry"))
             (recur (inc source-len)))

         :else (is (= ["12345" "filename" (cycle-bytes-hash [1 2])]
                      (do-read))))))))

(def entry-1-bytes (tree-entry-bytes "1" "file1" [0x01 0x10]))
(def entry-2-bytes (tree-entry-bytes "2" "file2" [0x02 0x20]))

(deftest test-read-tree
  (let [bytes (concat entry-1-bytes entry-2-bytes)
        entries [(read-tree-entry (byte-stream entry-1-bytes))
                 (read-tree-entry (byte-stream entry-2-bytes))]
        do-read #(read-tree (byte-stream (take % bytes)))]
    (are [entry-count byte-count]
      (= (take entry-count entries) (do-read byte-count))
      0 0
      1 (count entry-1-bytes)
      2 (+ (count entry-1-bytes) (count entry-2-bytes)))
    (are [ex-info-map byte-count]
      (thrown? ExceptionInfo (doall (do-read byte-count)))
      20
      (+ 20 (count entry-1-bytes)))))

(defn binary-hash [hash]
  (let [baos (java.io.ByteArrayOutputStream.)]
    (write-binary-hash hash baos)
    (ArrayBackedHash. (.toByteArray baos))))

(deftest test-read-tree*
  (with-temp-repo [git-dir]
    (let [blob-1-hash (write-blob "git" git-dir (.getBytes "blob-1" "UTF-8"))
          blob-2-hash (write-blob "git" git-dir (.getBytes "blob-2" "UTF-8"))
          hash-args (args git-dir ["hash-object" "-w" "--stdin" "-t" "tree"])
          [subt-in subt-out :as hash-subt] (invoke-streams "git" hash-args)
          [tree-in tree-out :as hash-tree] (invoke-streams "git" hash-args)]
      (future
        (write-tree-entry subt-in ["100644" "subfoo" blob-1-hash])
        (write-tree-entry subt-in ["100644" "subbar" blob-2-hash])
        (.close subt-in))
      (write-tree-entry tree-in ["100644" "foo" blob-1-hash])
      (write-tree-entry tree-in ["100644" "bar" blob-2-hash])
      (let [subt-hash (trim (slurp subt-out))]
        (write-tree-entry tree-in ["40000" "subt" subt-hash])
        (.close tree-in)
        (let [tree-hash (trim (slurp tree-out))]
          (throw-if-error hash-subt)
          (throw-if-error hash-tree)
          (transact/transact
           (fn [trans]
             (is (= [["100644" "foo" (binary-hash blob-1-hash)]
                     ["100644" "bar" (binary-hash blob-2-hash)]
                     ["40000" "subt" (binary-hash subt-hash)
                      [["100644" "subfoo" (binary-hash blob-1-hash)]
                       ["100644" "subbar" (binary-hash blob-2-hash)]]]]
                    (read-tree* git-dir tree-hash trans))))))))))

(deftest test-write-tree-entry
  (are [expected-bytes entry]
       (let [baos (new java.io.ByteArrayOutputStream)]
         (write-tree-entry baos entry)
         (is (= expected-bytes (into [] (.toByteArray baos)))))
       entry-1-bytes ["1" "file1" (cycle-bytes-hash [0x01 0x10])]
       entry-2-bytes ["2" "file2" (cycle-bytes-hash [0x02 0x20])]))

(deftest test-write-tree*
  (with-temp-repo [git-dir]
    (transact/transact
     (fn [trans]
       (let [hash-1 (binary-hash "582390cd4d97e31fa8749fd8b2748a7faaca0adc")
             hash-2 (binary-hash "d63488fe511f63c7c746c15447ac3e08c31cc023")
             hash-3 (binary-hash "d2cebd4f0a9e97a48a6139d09cafdb513ad8fee3")
             at-top-dir (.getBytes "at top dir\n" "UTF-8")
             in-dir (.getBytes "in dir\n" "UTF-8")
             hash (write-tree* git-dir
                               [["100644" "foo" nil at-top-dir]
                                ["40000" "bar" nil [["100644" "file" nil
                                                     in-dir]]]])]
         (is (= "1fd6436bd031da537877d1531ac474a939e907fb" hash))
         (is (= [["100644" "foo" hash-1]
                 ["40000" "bar" hash-2
                  [["100644" "file" hash-3]]]]
                (read-tree* git-dir hash trans))))
       (let [hash (write-tree* git-dir [])]
         (is (= "4b825dc642cb6eb9a060e54bf8d69288fbee4904" hash))
         (is (= [] (read-tree* git-dir hash trans))))))))

(def person "John Doe <jdoe@google.com> 1398561813 -0700")

(deftest test-read-commit
  (let [hash-1 (str (cycle-bytes-hash [1 2 3]))
        hash-2 (str (cycle-bytes-hash [4 5 6]))
        msg "heading\n\ndetails"
        do-read #(-> (apply str %)
                     (.getBytes "UTF-8")
                     java.io.ByteArrayInputStream.
                     read-commit
                     doall)]
    (are [result commit-text]
      (= result (do-read commit-text))
      [["tree" hash-1]
       ["parent" hash-2]
       ["author" person]
       ["committer" person]
       [:msg msg]]
      ["tree " hash-1 "\n"
       "parent " hash-2 "\n"
       "author " person "\n"
       "committer " person "\n\n"
       msg]

      []
      [])
    (are [commit-text]
      (thrown? ExceptionInfo (do-read commit-text))
      ["tree " hash-1 "\n"
       "parent 01234"]

      ["tree " hash-1 "\n"
       "parent"])))

(deftest test-read-commit-transact
  (with-temp-repo [git-dir]
    (transact/transact
     (fn [trans]
       (is (= 0 (count @trans)))
       (let [c (read-commit git-dir pretend-hash trans)
             err-substr (str "cat-file commit " pretend-hash)]
         (is (= 1 (count @trans)))
         (try
           (doseq [e c] nil)
           (throw (ex-info "should have thrown"))
           (catch ExceptionInfo e
             (is (not= -1 (.indexOf (.getMessage e) err-substr))))))))))

(defn commit-entry-string [e]
  (let [baos (new java.io.ByteArrayOutputStream)]
    (write-commit-entry baos e)
    (new String (.toByteArray baos) "UTF-8")))

(deftest test-write-commit-entry
  (let [hash-1 (cycle-bytes-hash [1 2 3])]
    (is (= "\nmsg title\n\ndetails"
           (commit-entry-string [:msg "msg title\n\ndetails"])))
    (is (= (str "parent " hash-1 "\n")
           (commit-entry-string ["parent" hash-1])))
    (is (= (str "author " person "\n")
           (commit-entry-string ["author" person])))))

(deftest test-invoke-result-false
  (are [x] (not (invoke-result? x))
       ""
       #{}
       {}
       {}
       {:exit 0 :out "a"}
       {:out "a" :err "a"}
       {:out "a" :err "a"}
       {:exit 0 :out "a" :err 42}
       {:exit 0 :out "a" :err "a" :extra-key "not allowed"}))

(deftest test-invoke-result-true
  (are [x] (invoke-result? x)
       {:exit 0 :out "a" :err "b"}
       {:exit -1 :out "" :err " "}))

(deftest test-args?-false
  (are [x] (not (args? x))
       0
       {}
       {"a" "b"}
       [:a :b]
       [" " nil]))

(deftest test-args?-true
  (are [x] (args? x)
       '()
       (lazy-seq ["a" "b" "c"])
       [""]
       ["init"]
       ["rev-parse"]
       '("checkout" "branch")))

(deftest test-args
  (is (= ["--git-dir=foodir" "a" "b" "c"]
         (args "foodir" ["a" "b" "c"]))))

(deftest test-invoke
  (with-temp-repo [repo-dir git-dir-flag]
    (let [rev-parse-result (invoke "git" [git-dir-flag "rev-parse"])]
      (is (= rev-parse-result {:err "" :out "" :exit 0}))
      (is (invoke-result? rev-parse-result)))))

(deftest test-invoke-streams-empty-err
  (with-temp-repo [repo-dir git-dir-flag]
    (let [[in out finish :as result]
          (invoke-streams "git" [git-dir-flag "hash-object" "-w" "--stdin"])]
      (.write in (.getBytes "hello\n" "UTF-8"))
      (is (not (realized? finish)))
      (.close in)
      (is (= "ce013625030ba8dba906f756967f9e9ca394464a\n" (slurp out)))
      (is (= {:err "" :exit 0}) @finish)
      (is (= (str "execute: git " git-dir-flag
                  " hash-object -w --stdin\nstderr:\nexit: 0"))
          (nth result 3)))))

(deftest test-invoke-streams-err
  (with-temp-repo [repo-dir git-dir-flag]
    (let [[_ _ result summary]
          (invoke-streams "git" [git-dir-flag "cat-file" "-t" "1234567"])
          err "fatal: Not a valid object name 1234567\n"]
      (is (= {:err err :exit 128} @result))
      (is (= (str "execute: git " git-dir-flag " cat-file -t 1234567\nstderr:\n"
                  err "exit: 128\n")
             summary)))))

(deftest test-if-error
  (let [invoke-res (fn [exit err]
                     [nil nil (future {:exit exit :err err}) "summary"])]
    (is (= "summary+extra"
           (if-error (invoke-res 13 "stderr") #(str % "+extra"))))
    (is (= 42 (if-error (invoke-res 0 "stderr") (constantly 42))))
    (is (= 42 (if-error (invoke-res 13 "") (constantly 42))))
    (is (nil? (if-error (invoke-res 0 "")
                        (fn [_] (throw (IllegalStateException.
                                        "Should not get here."))))))))

(deftest test-summary
  (let [err "[stderr contents]"
        out "[stdout contents]"
        exit 96
        result (summary ["arg1!" "?arg2"] {:err err :out out :exit exit})
        expected-substrs [err out (str exit) "git arg1! ?arg2"]]
    (doseq [substr expected-substrs]
      (is (not= -1 (.indexOf result substr)) substr))))

(deftest test-invoke-with-summary
  (let [result (invoke-with-summary "git" ["--version"])]
    (is (invoke-result? (first result)))
    (is (string? (second result)))
    (is (= 2 (count result)))))
