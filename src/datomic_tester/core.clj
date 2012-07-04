(ns datomic-tester.core
  (:use    [datomic.api :only [db q] :as d])
  (:import [datomic Peer Util] ;; Imports only used in data loading
           [java.io FileReader]))


(comment  ;; Stick everything in comment so it doesn't run in swank

  ;; Define URI
  ;; ---------
  ;; Persistent Dev setup, requires setting up transactor with config/samples/dev-transactor.properties
  (def conn-uri "datomic:dev://localhost:4334/seattle")
  ;; In-memory, gone after disconnect I imagine...
  (def conn-uri "datomic:mem://seattle")
  
  ;; Create database from URI
  (d/create-database conn-uri)

  ;; Connect to existing database via URI
  (def conn (d/connect conn-uri))

  ;; Load Sample data
  (def datomic-path "/home/abusby/Downloads/datomic-0.1.3164/")
  (do
    ;; Setup sample schema
    (.get (.transact conn
                     (first (Util/readAll
                             (FileReader. (str datomic-path "samples/seattle/seattle-schema.dtm"))))))

    ;; load sample data
    (.get (.transact conn
                     (first (Util/readAll
                             (FileReader. (str datomic-path "samples/seattle/seattle-data0.dtm"))))))

    ;; load more sample data
    (def future-data (first (Util/readAll
                             (FileReader. (str datomic-path "samples/seattle/seattle-data0.dtm")))))
    )
  
  ;; find all community record wit community name
  (q '[:find ?c
       :where
       [?c :community/name]]
     (db conn))

  ;; find all records,
  ;; then get entity's for each,
  ;; then extract the record name
  (map (comp :community/name
             #(d/entity (db conn) %)
             first)
       (q '[:find ?c :where [?c :community/name]]
          (db conn)))
  
  ;; Query query out the wanted fields
  (q '[:find ?n ?u
       :where
       [?c :community/name ?n]
       [?c :community/url ?u]]
     (db conn))

  ;; Define query parameters
  (q '[:find ?e ?c
       :where
       [?e :community/name "belltown"]
       [?e :community/category ?c]]
     (db conn))
  
  ;; Query by chained relationships
  (q '[:find ?c_name ?r_name
       :where
       [?c :community/name ?c_name]
       [?c :community/neighborhood ?n]
       [?n :neighborhood/district ?d]
       [?d :district/region ?r]
        [?r :db/ident ?r_name]]
     (db conn))

  ;; Retrieve an individual entity
  (d/entity (conn db) 17592186045516) ;; Where that giant number should be from one of the earlier record queries

  ;; Binding parameters to query
  (q '[:find ?n
       :in $ ?t
       :where
       [?c :community/name ?n]
       [?c :community/type ?t]]
     (db conn)
     :community.type/twitter)

  ;; Using functions in query
  (q '[:find ?n
       :where
       [?c :community/name ?n]
       [(.compareTo ^String ?n "C") ?res]
       [(< ?res 0)]]
     (db conn))

  ;; Or better yet, Clojure style functions
  (q '[:find ?n
       :where
       [?c :community/name ?n]
       [(#(< (.compareTo ^String % "C") 0) ?n)]]
     (db conn))

  ;; Using "full text search" capability, if defined as part of schema
  (q '[:find ?n
       :where
        [(fulltext $ :community/name "wallingford") [[?e ?n]]]]
     (db conn))

  ;; Parameterized full text search...
  (q '[:find ?name ?cat
       :in $ ?type ?search
       :where
       [?c :community/name ?name]
       [?c :community/type ?type]
        [(fulltext $ :community/category ?search) [[?c ?cat]]]]
     (db conn)
     :community.type/website
     "food")

  ;; Using rules, think of them as awesome SQL WHERE macros
  (def twitter-rule '[[[twitter ?c] [?c :community/type :community.type/twitter]]])
  (q '[:find ?n
       :in $ %
       :where
       [?c :community/name ?n]
       (twitter ?c)]
     (db conn)
     twitter-rule)

  ;; More complex rules
  (def community-to-region '[[[region ?c ?r]
                              [?c :community/neighborhood ?n]
                              [?n :neighborhood/district ?d]
                              [?d :district/region ?re]
                              [?re :db/ident ?r]]])  
  (q '[:find ?n
       :in $ %
       :where
       [?c :community/name ?n]
       (region ?c :region/ne)]
     (db conn)
     community-to-region)

  ;; Example of an OR in a rule
  (def or-rule-example '[[[social-media ?c]
                          [?c :community/type :community.type/twitter]]
                         [[social-media ?c]
                          [?c :community/type :community.type/facebook-page]]])

  ;; Rules can define rules in a hierachy
  (def hierarchical-rules '[[[region ?c ?r]
                             [?c :community/neighborhood ?n]
                             [?n :neighborhood/district ?d]
                             [?d :district/region ?re]
                             [?re :db/ident ?r]]
                            [[social-media ?c]
                             [?c :community/type :community.type/twitter]]
                            [[social-media ?c]
                             [?c :community/type :community.type/facebook-page]]
                            [[northern ?c] (region ?c :region/ne)]
                            [[northern ?c] (region ?c :region/n)]
                            [[northern ?c] (region ?c :region/nw)]
                            [[southern ?c] (region ?c :region/sw)]
                            [[southern ?c] (region ?c :region/s)]
                            [[southern ?c] (region ?c :region/se)]])
  (q '[:find ?n
       :in $ %
       :where
       [?c :community/name ?n]
       (northern ?c)
       (social-media ?c)]
     (db conn)
     hierarchical-rules)  
  
  ;; Find when transactions have executed  
  (q '[:find ?when
       :where
        [?tx :db/txInstant ?when]]
     (db conn))

  ;; Query and define the various DB states
  (def db-dates (->> (q '[:find ?when
                          :where
                          [?tx :db/txInstant ?when]]
                        (db conn))
                     seq
                     (map first)
                     sort))

  ;; Query against the past db state
  (count (q '[:find ?c :where [?c :community/name]]
            (d/as-of (db conn) (last db-dates))))
  (count (q '[:find ?c :where [?c :community/name]]
            (d/as-of (db conn) (first db-dates))))

  ;; Modifying the data queried against, but *WITHOUT* changing the DB
  (count (q '[:find ?c :where [?c :community/name]]
            (d/with (db conn) future-data)))


  ;; Transactions
  ;; ------------
  ;; Anything in a map counts as ADD/UPDATE (depending on if the id already exists or not)
  (d/transact conn
              '[{:db/id #db/id [:db.part/user]
                 :community/name
                 "Easton"}])

  ;; Add more than one record at a time
  (d/transact conn
              '[{:db/id #db/id [:db.part/user] :community/name "foo"}
                {:db/id #db/id [:db.part/user] :community/name "bar"}
                {:db/id #db/id [:db.part/user] :community/name "baz"}])
  
  ;; Transaction, update records
  (let [baz-id (-> (q '[:find ?c
                        :where
                        [?c :community/name "baz"]]
                      (db conn))
                   first
                   first)] ;; get the ID to modify
    (d/transact conn ;; Note use back-tick, so we can unquote the value of baz-id
                `[{:db/id ~baz-id :community/name "bazzer"}]))

  ;; Transaction, delete record
  (let [val    "foo"
        val-id (-> (q `[:find ?c
                        :where
                        [?c :community/name ~val]]
                      (db conn))
                   first
                   first)] ;; get the ID to modify
    (d/transact conn ;; Note use back-tick, so we can unquote the value of baz-id
                `[[:db/retract ~val-id :community/name ~val]]))

  
  ;; Syntax to define a schema
  ;; Name/Type/Cardinality/doc/install
  ;; with fulltext and unique being optional  
  (def music-schema
    [
     ;; ARTIST
     {:db/id #db/id [:db.part/db]
      :db/ident :artist/name
      :db/valueType :db.type/string
      :db/cardinality :db.cardinality/one
      :db/fulltext true
      :db/doc "Artist Name"
      :db.install/_attribute :db.part/db}
     
     {:db/id #db/id [:db.part/db]
      :db/ident :artist/yomi
      :db/valueType :db.type/string
      :db/cardinality :db.cardinality/one
      :db/fulltext true
      :db/doc "Artist Yomi"
      :db.install/_attribute :db.part/db}

     {:db/id #db/id [:db.part/db]
      :db/ident :artist/id
      :db/valueType :db.type/long
      :db/cardinality :db.cardinality/one
      :db/unique :db.unique/identity
      :db/doc "External Artist ID"
      :db.install/_attribute :db.part/db}

     ;; ALBUM
     {:db/id #db/id [:db.part/db]
      :db/ident :album/name
      :db/valueType :db.type/string
      :db/cardinality :db.cardinality/one
      :db/fulltext true
      :db/doc "Album Name"
      :db.install/_attribute :db.part/db}     

     {:db/id #db/id [:db.part/db]
      :db/ident :album/yomi
      :db/valueType :db.type/string
      :db/cardinality :db.cardinality/one
      :db/fulltext true
      :db/doc "Album Yomi"
      :db.install/_attribute :db.part/db}

     {:db/id #db/id [:db.part/db]
      :db/ident :album/id
      :db/valueType :db.type/long
      :db/cardinality :db.cardinality/one
      :db/unique :db.unique/identity
      :db/doc "External Album ID"
      :db.install/_attribute :db.part/db}

     {:db/id #db/id [:db.part/db]
      :db/ident :album/artist
      :db/valueType :db.type/ref
      :db/cardinality :db.cardinality/one
      :db/doc "Album's Artist"
      :db.install/_attribute :db.part/db}

     {:db/id #db/id [:db.part/db]
      :db/ident :album/volume
      :db/valueType :db.type/long
      :db/cardinality :db.cardinality/one
      :db/doc "Album Volume"
      :db.install/_attribute :db.part/db}

     ;; TRACK
     {:db/id #db/id [:db.part/db]
      :db/ident :track/name
      :db/valueType :db.type/string
      :db/cardinality :db.cardinality/one
      :db/fulltext true
      :db/doc "Track Name"
      :db.install/_attribute :db.part/db}     

     {:db/id #db/id [:db.part/db]
      :db/ident :track/yomi
      :db/valueType :db.type/string
      :db/cardinality :db.cardinality/one
      :db/fulltext true
      :db/doc "Track Yomi"
      :db.install/_attribute :db.part/db}

     {:db/id #db/id [:db.part/db]
      :db/ident :track/id
      :db/valueType :db.type/long
      :db/cardinality :db.cardinality/one
      :db/unique :db.unique/identity
      :db/doc "External Track ID"
      :db.install/_attribute :db.part/db}

     {:db/id #db/id [:db.part/db]
      :db/ident :track/num
      :db/valueType :db.type/long
      :db/cardinality :db.cardinality/one
      :db/doc "Track Num"
      :db.install/_attribute :db.part/db}

     {:db/id #db/id [:db.part/db]
      :db/ident :track/album
      :db/valueType :db.type/ref
      :db/cardinality :db.cardinality/one
      :db/doc "Track's Album"
      :db.install/_attribute :db.part/db}
     
     {:db/id #db/id [:db.part/db]
      :db/ident :track/artist
      :db/valueType :db.type/ref
      :db/cardinality :db.cardinality/one
      :db/doc "Track's Artist"
      :db.install/_attribute :db.part/db}
     ])

  ;; Apply Schema
  (d/transact conn music-schema)

  ;; Add some relation data to datomic
  ;; ---------------------------------
  
  ;; This helps
  (defn create-rec [data]
    "Given a map, generates the associated datalog record
     and returns [rec-tempid datalog]"
    (let [eid (d/tempid :db.part/user)]
      [eid (map (fn [[k v]]
                  `{:db/id ~eid ~k ~v})
                data)]))

  ;; Now generate the datalog/sql and submit
  (let [[artist-eid artist-sql] (create-rec {:artist/id   166
                                             :artist/name "Nirvana"
                                             :artist/yomi "ニルバーナ"})
        [album-eid album-sql]   (create-rec {:album/id    5
                                 :album/name "Nevermind"
                                 :album/yomi "ネバーマインド"
                                 :album/volume 999999999
                                 :album/artist artist-eid})
        track-details           [["Smells Like Teen Spirit" 1 101]
                                 ["In Bloom" 2 102]
                                 ["Come As You Are" 3 103]
                                 ["Breed" 4 104]
                                 ["Lithium" 5 105]
                                 ["Polly" 6 106]
                                 ["Territorial Pissings" 7 107]
                                 ["Drain You" 8 108]
                                 ["Lounge Act" 9 109]
                                 ["Stay Away" 10 110]
                                 ["On A Plain" 11 111]
                                 ["Something In The Way, with the hidden track" 12 112]]        
        track-sql               (->> track-details
                                     (map (fn [trk] (->> (conj trk album-eid)
                                                         (zipmap [:track/name :track/num :track/id :track/album])
                                                         create-rec
                                                         second)))
                                     flatten)
        sql (vec (concat artist-sql album-sql track-sql))
        ]
    (d/transact conn sql))
  

  ;; Submit an annotated transaction
  ;; -------------------------------

  ;; Modify schema to include extra transaction data
  (d/transact conn
              '[{:db/id #db/id[:db.part/db]
                 :db/ident :data/awesomeness
                 :db/valueType :db.type/string
                 :db/cardinality :db.cardinality/one
                 :db/doc "How awesome is this transaction"
                 :db.install/_attribute :db.part/db}
                ])

  ;; Submit transaction with extra transaction data
  (d/transact conn
              '[{:db/id #db/id[:db.part/user]
                 :community/name "foo2"}
                {:db/id #db/id[:db.part/tx]
                 :data/awesomeness "Totally awesome!"}])  

  ;; Find our awesome new transaction
  (q '[:find ?c ?n :where [?c :data/awesomeness ?n]] (db conn))

  
  ) ;; END COMMENT SECTION
