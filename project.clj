(defproject datomic-tester "1.0.0-SNAPSHOT"
  :description "For playing with Datomic, and for reference"
  :dependencies [[org.clojure/clojure "1.5.0-alpha2"]

                 ;; NOTE, you must do the following command from your datomic installation for this to work,
                 ;; $ mvn install:install-file -DgroupId=com.datomic -DartifactId=datomic -Dfile=datomic-${DATOMIC_VERSION}.jar 
                 [com.datomic/datomic "0.1.3164"]] 
  :dev-dependencies [[swank-clojure "1.4.0"]]
  :jvm-opts ["-Dswank.encoding=utf-8"]
  :warn-on-reflection true
  :disable-implicit-clean true)