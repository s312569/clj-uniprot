(defproject clj-uniprot "0.1.8"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [clj-time "0.11.0"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [org.clojure/data.xml "0.0.8"]
                 [com.taoensso/nippy "2.11.1"]
                 [org.clojure/data.zip "0.1.1"]
                 [biodb "0.1.3"]
                 [clj-http "3.0.0"]
                 [fs "1.3.3"]]
  :repl-options {:init (set! *print-length* 100)}
  :jvm-opts ["-Xmx1000M"])
