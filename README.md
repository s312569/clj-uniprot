# clj-uniprot

A parser for Uniprot sequences in XML format.

## Usage

Import from Clojars:

```clojure
[clj-uniprot "0.1.3"]
```

Use in your namespace:

```clojure
(:require [clj-uniprot.core :as up])
```

Open a reader on a file containing Uniprot sequences in XML format and
call 'uniprot-seq'. This will return a lazy list of zippers, one for
each sequence in the file, that can be used with the usual Clojure XML
parsing libraries.

```clojure
user> (with-open [r (reader "/uniprot/file.xml")]
        (doall (->> (uniprot-seq r)
                    (take 5)
                    (map accession))))
("Q4U9M9" "P15711" "Q6V4H0" "Q43495" "P13813")
user>
```

Some accessors are defined (accessions, accession, description and
tax-name) and 'biosequence' returns the sequence of the protein as a
string. Others will be added as I need them.

Uniprot can be searched remotely using 'uniprot-search' which returns
a list of accessions matching your search. Sequences can be fetched
from Uniprot using 'get-uniprot-sequence'. This returns a buffered
reader that can be directly used with 'with-open' and 'uniprot-seq'.

```clojure
clj-uniprot.core> (with-open [r (get-uniprot-sequences "jason.mulvenna@gmail.com"
                                                       '("P68371"))]
                    (doall (->> (uniprot-seq r)
                                (map accession))))
("P68371")
clj-uniprot.core>
```

Sequences can be converted to a fasta string using 'uniprot->fasta'.

## License

Copyright © 2016 Jason Mulvenna

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
