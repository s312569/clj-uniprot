(ns clj-uniprot.core
  (:require [clojure.data.xml :refer [parse]]
            [clojure.zip :refer [xml-zip]]
            [clojure.data.zip.xml :refer [xml-> xml1-> text attr=]]
            [clojure.string :refer [trim replace split]]
            [fs.core :refer [temp-file delete]]
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

(defn tax-name
  "Takes a zipper and returns the scientific name of an organism
  associated with a Uniprot sequence."
  [up]
  (xml1-> up :organism :name (attr= :type "scientific") text))

(defn accessions
  "Takes a zipper and returns a list of accessions associated with a
  Uniprot sequence."
  [up]
  (xml-> up :accession text))

(defn accession
  "Takes a zipper and returns the first accession associated with a
  Uniprot sequence."
  [up]
  (first (accessions up)))

(defn biosequence
  "Takes a zipper and returns the protein sequence associated with a
  Uniprot sequence."
  [up]
  (-> (xml1-> up :sequence text)
      (replace #" " "")))

(defn description
  "Takes a zipper and returns a description of a Uniprot
  sequence. Includes recommended name and species or, if no
  recommended name, other information contained is used to assemble a
  description."
  [up]
  (let [rn (or (xml1-> up :protein :recommendedName :fullName text)
               (some #(xml1-> up :protein % )
                     '(:alternativeName
                       :submittedName
                       :allergenName
                       :biotechName
                       :cdAntigenName
                       :innName))
               "Unknown")]
    (str rn " [" (tax-name up) "]")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; formats
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn uniprot->fasta
  "Converts a Uniprot sequence to a fasta string."
  [up]
  (str ">" (accession up) " " (description up) \newline
       (doall (->> (partition-all 70 (biosequence up))
                   (map #(apply str %))
                   (interpose \newline)
                   (apply str)))))

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
                      (split #"\n")))]
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
                       r
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
  'uniprot-seq'."
  [email accessions]
  (if (empty? accessions)
    nil
    (let [f (let [file (temp-file "up-seq-")]
              (->> (interpose \newline accessions)
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
