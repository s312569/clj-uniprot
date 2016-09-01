(ns clj-uniprot.core
  (:require [clojure.data.xml :refer [parse]]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clojure.zip :refer [xml-zip node]]
            [clojure.data.zip.xml :refer [xml-> xml1-> text attr= attr]]
            [clojure.string :as st]
            [biodb.core :as bdb]
            [taoensso.nippy :refer [freeze thaw]]
            [fs.core :refer [temp-file delete]]
            [clojure.edn :as edn]
            [clj-http.client :as client]))

(defn uniprot-seq
  "Takes a buffered reader on a Uniprot XML formatted file and returns
  a lazy list of zippers corresponding to sequences in the file."
  [reader]
  (->> (filter #(= (:tag %) :entry) (:content (parse reader)))
       (map xml-zip)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; some accessors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- format-date
  [s]
  (let [fo (f/formatter "yyyy-MM-dd")]
    (f/parse fo s)))

(defn dataset
  [up]
  "Returns the dataset the protein belongs to, e.g. Swiss-Prot."
  (xml1-> up (attr :dataset)))

(defn created
  [up]
  "Returns the creation date. Returns a joda time object."
  (format-date (xml1-> up (attr :created))))

(defn modified
  [up]
  "Returns the modification date. Returns a joda time object."
  (format-date (xml1-> up (attr :modified))))

(defn version
  [up]
  "Returns the version."
  (xml1-> up (attr :version)))

(defn tax-name
  "Takes a zipper and returns the scientific name of an organism
  associated with a Uniprot sequence."
  [up]
  (xml1-> up :organism :name (attr= :type "scientific") text))

(defn accessions
  "Takes a zipper and returns a list of accessions associated with a
  Uniprot sequence."
  [up]
  (if up
    (xml-> up :accession text)))

(defn accession
  "Takes a zipper and returns the first accession associated with a
  Uniprot sequence."
  [up]
  (if (seq up)
    (first (accessions up))))

(defn biosequence
  "Takes a zipper and returns the protein sequence associated with a
  Uniprot sequence."
  [up]
  (-> (xml1-> up :sequence text)
      (st/replace #" " "")))

(defn description
  "Takes a zipper and returns a description of a Uniprot
  sequence. Includes recommended name and species or, if no
  recommended name, other information contained is used to assemble a
  description."
  [up]
  (let [rn (or (xml1-> up :protein :recommendedName :fullName text)
               (some #(xml1-> up :protein % text)
                     '(:alternativeName
                       :submittedName
                       :allergenName
                       :biotechName
                       :cdAntigenName
                       :innName))
               "Unknown")]
    (str rn " [" (tax-name up) "]")))

(defn db-x-refs
  [up]
  "Returns a list of database cross-references. Returns maps
  with :type, :id and :properties keys."
  (->> (xml-> up :dbReference)
       (map #(hash-map :type (xml1-> % (attr :type))
                       :id (xml1-> % (attr :id))
                       :properties (->> (xml-> % :property node)
                                        (map :attrs))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; formats
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn uniprot->fasta
  "Converts a Uniprot sequence to a fasta hashmap."
  [up]
  {:accession (accession up)
   :description (description up)
   :sequence (biosequence up)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; remote
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn uniprot-search
  "Submits a search to Uniprot and returns a non-lazy list of uniprot
  accession numbers satisfying the search term.
   The search term uses the same syntax as the uniprot web interface. For 
   example:
   - to get all Schistosoma mansoni proteins in the proteome reference set term
     would be:
     'organism:6183 AND keyword:1185'
   - get all Schistosoma mansoni proteins in the proteome set that are intrinsic
     to the membrane:
     'taxonomy:6183 AND keyword:1185 AND go:0031224'
   - get all reviewed human entries:
     'reviewed:yes AND organism:9606'
   And so on. Returns an empty list if no matches found. Uniprot
   requires an email so an email can be supplied using the email argument."
  ([term email] (uniprot-search term email 0))
  ([term email offset]
   (let [r (remove #(= % "")
                    (-> (client/get (str "http://www.uniprot.org/uniprot/?query="
                                         term
                                         "&format=list"
                                         (str "&offset=" offset)
                                         "&limit=1000")
                             {:client-params {"http.useragent"
                                              (str "clj-http " email)}})
                      (:body)
                      (st/split #"\n")))]
     (cond (< (count r) 1000) r
           (not (seq r)) nil
           :else
           (concat r (lazy-cat (uniprot-search term email (+ offset 1000))))))))

(defn- uniprot-process-request
  [address params file]
  (let [p (client/post address params)]
    (letfn [(check [a c]
              (let [r (client/get a {:follow-redirects false :as :stream})]
                (cond
                 (nil? (get (:headers r) "retry-after"))
                 (cond (= (:status r) 200)
                       (if (= (get (:headers r) "Content-Type") "application/xml")
                         r
                         (throw (Exception. (str "No responses for query."))))
                       (= (:status r) 302)
                       (recur (get (:headers r) "Location") 0)
                       :else
                       (throw (Throwable. (str "Error in sequence retrieval: code"
                                               (:status r)))))
                 (> c 50)
                 (throw (Throwable. "Too many tries."))
                 :else
                 (recur (do (Thread/sleep 10000) a) (inc c)))))]
      (if (some #(= (:status p) %) '(302 303))
        (do
          (if (get (:headers p) "retry-after")
            (Thread/sleep (read-string (get (:headers p) "retry-after"))))
          (check (get (:headers p) "location") 0))
        (throw (Throwable. (str "Error in sequence retrieval" p)))))))

(defn get-uniprot-sequences
  "Takes a list of accessions and returns a stream from Uniprot
  containing the sequences in XML format. Can then be used with
  'with-open' and then 'uniprot-seq'. Throws an exception if no
  sequences returned."
  [email accessions]
  (if (empty? accessions)
    nil
    (let [f (let [file (temp-file "up-seq-")]
              (->> (map #(first (st/split % #"-")) accessions)
                   (interpose \newline)
                   (apply str)
                   (spit file))
              file)]
      (try
        (:body
         (uniprot-process-request "http://www.uniprot.org/batch/"
                                  {:client-params {"http.useragent"
                                                   (str "clj-http " email)}
                                   :multipart [{:name "file" :content f}
                                               {:name "format" :content "xml"}]
                                   :follow-redirects false}
                                  f))
        (finally (delete f))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; integration with biodb
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod bdb/table-spec :uniprot
  [q]
  (vector [:accession :text "PRIMARY KEY"]
          [:src :binary "NOT NULL"]))

(defmethod bdb/prep-sequences :uniprot
  [q]
  (->> (:coll q)
       (map #(hash-map :accession (accession %) :src (freeze (node %))))))

(defmethod bdb/restore-sequence :uniprot
  [q]
  (xml-zip (thaw (:src (dissoc q :type)))))
