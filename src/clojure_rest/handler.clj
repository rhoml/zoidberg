    (ns clojure-rest.handler
      (:import com.mchange.v2.c3p0.ComboPooledDataSource)
      (:use compojure.core)
      (:use cheshire.core)
      (:use ring.util.response)
      (:require [compojure.handler :as handler]
                [ring.middleware.json :as middleware]
                [clojure.java.jdbc :as sql]
                [compojure.route :as route]))

    (def db-config
      {:classname "org.h2.Driver"
       :subprotocol "h2"
       :subname "mem:zoidberg"
       :user ""
       :password ""})

    (defn pool
      [config]
      (let [cpds (doto (ComboPooledDataSource.)
                   (.setDriverClass (:classname config))
                   (.setJdbcUrl (str "jdbc:" (:subprotocol config) ":" (:subname config)))
                   (.setUser (:user config))
                   (.setPassword (:password config))
                   (.setMaxPoolSize 1)
                   (.setMinPoolSize 1)
                   (.setInitialPoolSize 1))]
        {:datasource cpds}))

    (def pooled-db (delay (pool db-config)))

    (defn db-connection [] @pooled-db)

    (sql/with-connection (db-connection)
     ; (sql/drop-table :users) ; no need to do that for in-memory databases
      (sql/create-table :users [:id "varchar(256)" "primary key"]
                               [:username "varchar(1024)"]))

    (defn uuid [] (str (java.util.UUID/randomUUID)))

    (defn get-all-users []
      (response
        (sql/with-connection (db-connection)
          (sql/with-query-results results
            ["select * from users"]
            (into [] results)))))

    (defn get-user [id]
      (sql/with-connection (db-connection)
        (sql/with-query-results results
          ["select * from user where id = ?" id]
          (cond
            (empty? results) {:status 404}
            :else (response (first results))))))

    (defn create-new-user [user]
      (let [id (uuid)]
        (sql/with-connection (db-connection)
          (let [user (assoc user "id" id)]
            (sql/insert-record :users user)))
        (get-user id)))

    (defn update-user [id user]
        (sql/with-connection (db-connection)
          (let [user (assoc user "id" id)]
            (sql/update-values :users ["id=?" id] user)))
        (get-user id))

    (defn delete-user [id]
      (sql/with-connection (db-connection)
        (sql/delete-rows :users ["id=?" id]))
      {:status 204})

    (defroutes app-routes
      (context "/users" [] (defroutes users-routes
        (GET  "/" [] (get-all-users))
        (POST "/" {body :body} (create-new-user body))
        (context "/:id" [id] (defroutes user-routes
          (GET    "/" [] (get-user id))
          (PUT    "/" {body :body} (update-user id body))
          (DELETE "/" [] (delete-user id))))))
      (route/not-found "Not Found"))

    (def app
        (-> (handler/api app-routes)
            (middleware/wrap-json-body)
            (middleware/wrap-json-response)))