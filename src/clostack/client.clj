(ns clostack.client
  "A mostly generated wrapper to the cloudstack API."
  (:require [clojure.string   :as s]
            [cheshire.core    :as json]
            [net.http.client  :as http]
            [clostack.config  :as config]
            [clostack.payload :as payload]))

(defn http-client
  "Create an HTTP client"
  ([]
   (http-client {}))
  ([{:keys [config client]}]
   {:config (or config (config/init))
    :client (or client (http/build-client))}))

(defn json-response?
  "Ensure that the response is JSON"
  [{:keys [headers] :as resp}]
  (let [ctype (get headers :content-type)]
    (or (.contains ctype "javascript")
        (.contains ctype "json"))))

(defn parse-response
  "Ensure that response is JSON-formatted, if so parse it"
  [resp]
  (if (json-response? resp)
    (update-in resp [:body] json/parse-string true)
    resp))

(defn api-name
  "Given a hyphenated name, yield a camel case one"
  [s]
  (let [[prelude & rest] (s/split (name s) #"-")
        capitalizer      #(if (#{"lb" "ssh" "vpc" "vm"} %)
                            (s/upper-case %)
                            (s/capitalize %))]
    (apply str prelude (map capitalizer rest))))

(defn async-request
  "Asynchronous request, will execute handler when response comes back."
  ([client opcode handler]
   (async-request client opcode {} handler))
  ([{:keys [config client]} opcode args handler]
   (let [op       (if (keyword? opcode) (api-name opcode) opcode)
         payload  (payload/build-payload config (api-name opcode) args)
         callback (comp handler parse-response)
         headers  {"Content-Type"  "application/x-www-form-urlencoded"}
         req-map  {:uri            (:endpoint config)
                   :request-method :post
                   :headers        headers
                   :body           payload}]
     (http/async-request client req-map callback))))

(defn request
  "Perform a synchronous HTTP request against the API"
  ([client opcode]
   (request client opcode {}))
  ([client opcode args]
   (let [p       (promise)
         handler (fn [response] (deliver p response))]
     (async-request client opcode args handler)
     (deref p))))

(defmacro with-response
  "Perform an asynchronous response, using body as the function body
   to execute."
  [[sym client opcode args] & body]
  `(async-request
    ~client
    ~opcode
    ~(or args {})
    (fn [~sym] ~@body)))

(defn paging-request
  "Perform a paging request. Elements are fetched by chunks of 100."
  ([client op]
   (paging-request client op {} 1 nil))
  ([client op args]
   (paging-request client op args 1 nil))
  ([client op args page width]
   (when (or (nil? width) (pos? width))
     (let [resp     (request client op (assoc args :page page :pagesize 500))
           success? (= 2 (quot (:status resp) 100))]
       (when-not success?
         (throw (ex-info "could not perform paging request" {:resp resp})))
       (let [desc     (->> resp :body (map val) (filter map?) first)
             width    (or width (:count desc))
             elems    (->> desc (map val) (filter vector?) first)
             pending  (- width (count elems))]
         (lazy-cat elems (paging-request client op args (inc page) pending)))))))
